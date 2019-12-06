/*
 * Copyright 2018-2019 Scala Steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.update

import cats.Monad
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import org.scalasteward.core.coursier.CoursierAlg
import org.scalasteward.core.data.{Dependency, GroupId, Update}
import org.scalasteward.core.nurture.PullRequestRepository
import org.scalasteward.core.repocache.{RepoCache, RepoCacheRepository}
import org.scalasteward.core.update.data.UpdateState
import org.scalasteward.core.update.data.UpdateState._
import org.scalasteward.core.util
import org.scalasteward.core.util.Nel
import org.scalasteward.core.vcs.data.PullRequestState.Closed
import org.scalasteward.core.vcs.data.Repo

final class UpdateAlg[F[_]](
    implicit
    coursierAlg: CoursierAlg[F],
    filterAlg: FilterAlg[F],
    logger: Logger[F],
    pullRequestRepo: PullRequestRepository[F],
    repoCacheRepository: RepoCacheRepository[F],
    updateRepository: UpdateRepository[F],
    F: Monad[F]
) {
  def checkForUpdates(repos: List[Repo]): F[List[Update.Single]] =
    updateRepository.deleteAll >>
      repoCacheRepository.getDependencies(repos).flatMap { dependencies =>
        val filteredDependencies =
          dependencies.filter(d => FilterAlg.isIgnoredGlobally(d.toUpdate).isRight)

        val updates = filteredDependencies.traverseFilter { dependency =>
          coursierAlg.getNewerVersions(dependency).map(Nel.fromList).flatMap { maybeNewerVersions =>
            val maybeUpdate = maybeNewerVersions.map { newerVersions =>
              dependency.toUpdate.copy(newerVersions = newerVersions.map(_.value))
            }
            maybeUpdate.flatTraverse(filterAlg.globalFilterOne)
          }
        }

        updates.flatTap { updates =>
          logger.info(util.logger.showUpdates(updates.widen[Update])) >>
            updateRepository.saveMany(updates)
        }
      }

  def filterByApplicableUpdates(repos: List[Repo], updates: List[Update.Single]): F[List[Repo]] =
    repos.filterA(needsAttention(_, updates))

  def needsAttention(repo: Repo, updates: List[Update.Single]): F[Boolean] =
    for {
      allStates <- findAllUpdateStates(repo, updates)
      outdatedStates = allStates.filter {
        case DependencyOutdated(_, _)     => true
        case PullRequestOutdated(_, _, _) => true
        case _                            => false
      }
      isOutdated = outdatedStates.nonEmpty
      _ <- {
        if (isOutdated) {
          val statesAsString = util.string.indentLines(outdatedStates.map(_.toString).sorted)
          logger.info(s"Update states for ${repo.show}:\n" + statesAsString)
        } else F.unit
      }
    } yield isOutdated

  def findAllUpdateStates(repo: Repo, updates: List[Update.Single]): F[List[UpdateState]] =
    repoCacheRepository.findCache(repo).flatMap {
      case Some(repoCache) =>
        val dependencies = repoCache.dependencies
        dependencies.traverse { dependency =>
          findUpdateState(repo, repoCache, dependency, updates)
        }
      case None => List.empty[UpdateState].pure[F]
    }

  def findUpdateState(
      repo: Repo,
      repoCache: RepoCache,
      dependency: Dependency,
      updates: List[Update.Single]
  ): F[UpdateState] =
    updates.find(UpdateAlg.isUpdateFor(_, dependency)) match {
      case None => F.pure(DependencyUpToDate(dependency))
      case Some(update) =>
        repoCache.maybeRepoConfig.map(_.updates.keep(update)) match {
          case Some(Left(reason)) =>
            F.pure(UpdateRejectedByConfig(dependency, reason))
          case _ =>
            pullRequestRepo.findPullRequest(repo, dependency, update.nextVersion).map {
              case None =>
                DependencyOutdated(dependency, update)
              case Some((uri, _, state)) if state === Closed =>
                PullRequestClosed(dependency, update, uri)
              case Some((uri, baseSha1, _)) if baseSha1 === repoCache.sha1 =>
                PullRequestUpToDate(dependency, update, uri)
              case Some((uri, _, _)) =>
                PullRequestOutdated(dependency, update, uri)
            }
        }
    }
}

object UpdateAlg {
  def isUpdateFor(update: Update, dependency: Dependency): Boolean =
    update.groupId === dependency.groupId &&
      update.artifactIds.contains_(dependency.artifactId) &&
      update.currentVersion === dependency.version

  def getNewerGroupId(currentGroupId: GroupId, artifactId: String): Option[(GroupId, String)] =
    Some((currentGroupId.value, artifactId)).collect {
      case ("org.spire-math", "kind-projector")   => (GroupId("org.typelevel"), "0.10.0")
      case ("com.github.mpilquist", "simulacrum") => (GroupId("org.typelevel"), "1.0.0")
      case ("com.geirsson", "sbt-scalafmt")       => (GroupId("org.scalameta"), "2.0.0")
      case ("net.ceedubs", "ficus")               => (GroupId("com.iheart"), "1.3.4")
    }

  def findUpdateUnderNewGroup(dep: Dependency): Option[Update.Single] =
    getNewerGroupId(dep.groupId, dep.artifactId).map {
      case (newId, fromVersion) =>
        dep.toUpdate.copy(newerGroupId = Some(newId), newerVersions = util.Nel.of(fromVersion))
    }
}
