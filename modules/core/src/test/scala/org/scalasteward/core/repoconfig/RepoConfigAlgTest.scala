package org.scalasteward.core.repoconfig

import better.files.File
import eu.timepit.refined.types.numeric.PosInt
import org.scalasteward.core.TestSyntax._
import org.scalasteward.core.data.{GroupId, Update}
import org.scalasteward.core.mock.MockContext.repoConfigAlg
import org.scalasteward.core.mock.MockState
import org.scalasteward.core.util.Nel
import org.scalasteward.core.vcs.data.Repo
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class RepoConfigAlgTest extends AnyFunSuite with Matchers {
  test("config with all fields set") {
    val repo = Repo("fthomas", "scala-steward")
    val configFile = File.temp / "ws/fthomas/scala-steward/.scala-steward.conf"
    val content =
      """|updates.allow  = [ { groupId = "eu.timepit"} ]
         |updates.pin  = [
         |                 { groupId = "eu.timepit", artifactId = "refined.1", version = "0.8." },
         |                 { groupId = "eu.timepit", artifactId = "refined.2", version = { prefix="0.8." } },
         |                 { groupId = "eu.timepit", artifactId = "refined.3", version = { suffix="jre" } },
         |                 { groupId = "eu.timepit", artifactId = "refined.4", version = { prefix="0.8.", suffix="jre" } }
         |               ]
         |updates.ignore = [ { groupId = "org.acme", version = "1.0" } ]
         |updates.limit = 4
         |updates.includeScala = true
         |updates.fileExtensions = [ ".txt" ]
         |pullRequests.frequency = "@weekly"
         |commits.message = "Update ${artifactName} from ${currentVersion} to ${nextVersion}"
         |""".stripMargin
    val initialState = MockState.empty.add(configFile, content)
    val config = repoConfigAlg.readRepoConfigWithDefault(repo).runA(initialState).unsafeRunSync()

    config shouldBe RepoConfig(
      pullRequests = PullRequestsConfig(frequency = Some(PullRequestFrequency.Timespan(7.days))),
      updates = UpdatesConfig(
        allow = List(UpdatePattern(GroupId("eu.timepit"), None, None)),
        pin = List(
          UpdatePattern(
            GroupId("eu.timepit"),
            Some("refined.1"),
            Some(UpdatePattern.Version(Some("0.8."), None))
          ),
          UpdatePattern(
            GroupId("eu.timepit"),
            Some("refined.2"),
            Some(UpdatePattern.Version(Some("0.8."), None))
          ),
          UpdatePattern(
            GroupId("eu.timepit"),
            Some("refined.3"),
            Some(UpdatePattern.Version(None, Some("jre")))
          ),
          UpdatePattern(
            GroupId("eu.timepit"),
            Some("refined.4"),
            Some(UpdatePattern.Version(Some("0.8."), Some("jre")))
          )
        ),
        ignore = List(
          UpdatePattern(GroupId("org.acme"), None, Some(UpdatePattern.Version(Some("1.0"), None)))
        ),
        limit = Some(PosInt.unsafeFrom(4)),
        includeScala = Some(true),
        fileExtensions = Some(List(".txt"))
      ),
      commits = CommitsConfig(
        message = Some("Update ${artifactName} from ${currentVersion} to ${nextVersion}")
      )
    )
  }

  test("config with 'updatePullRequests = false'") {
    val content = "updatePullRequests = false"
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(RepoConfig(updatePullRequests = Some(PullRequestUpdateStrategy.Never)))
  }

  test("config with 'updatePullRequests = true'") {
    val content = "updatePullRequests = true"
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(
      RepoConfig(updatePullRequests = Some(PullRequestUpdateStrategy.OnConflicts))
    )
  }

  test("config with 'updatePullRequests = always'") {
    val content = """updatePullRequests = "always" """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(RepoConfig(updatePullRequests = Some(PullRequestUpdateStrategy.Always)))
  }

  test("config with 'updatePullRequests = on-conflicts'") {
    val content = """updatePullRequests = "on-conflicts" """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(
      RepoConfig(updatePullRequests = Some(PullRequestUpdateStrategy.OnConflicts))
    )
  }

  test("config with 'updatePullRequests = never'") {
    val content = """updatePullRequests = "never" """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(RepoConfig(updatePullRequests = Some(PullRequestUpdateStrategy.Never)))
  }

  test("config with 'updatePullRequests = foo'") {
    val content = """updatePullRequests = foo """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(RepoConfig(updatePullRequests = Some(PullRequestUpdateStrategy.default)))
  }

  test("config with 'pullRequests.frequency = @asap'") {
    val content = """pullRequests.frequency = "@asap" """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(
      RepoConfig(pullRequests = PullRequestsConfig(frequency = Some(PullRequestFrequency.Asap)))
    )
  }

  test("config with 'pullRequests.frequency = @daily'") {
    val content = """pullRequests.frequency = "@daily" """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(
      RepoConfig(pullRequests =
        PullRequestsConfig(frequency = Some(PullRequestFrequency.Timespan(1.day)))
      )
    )
  }

  test("config with 'pullRequests.frequency = @monthly'") {
    val content = """pullRequests.frequency = "@monthly" """
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(
      RepoConfig(pullRequests =
        PullRequestsConfig(frequency = Some(PullRequestFrequency.Timespan(30.days)))
      )
    )
  }

  test("config with 'scalafmt.runAfterUpgrading = true'") {
    val content = "scalafmt.runAfterUpgrading = true"
    val config = RepoConfigAlg.parseRepoConfig(content)
    config shouldBe Right(
      RepoConfig(scalafmt = ScalafmtConfig(runAfterUpgrading = Some(true)))
    )
  }

  test("malformed config") {
    val repo = Repo("fthomas", "scala-steward")
    val configFile = File.temp / "ws/fthomas/scala-steward/.scala-steward.conf"
    val initialState = MockState.empty.add(configFile, """updates.ignore = [ "foo """)
    val (state, config) =
      repoConfigAlg.readRepoConfigWithDefault(repo).run(initialState).unsafeRunSync()

    config shouldBe RepoConfig()
    state.logs.headOption.map { case (_, msg) => msg }.getOrElse("") should
      startWith("Failed to parse .scala-steward.conf")
  }

  test("configToIgnoreFurtherUpdates with single update") {
    val update = Update.Single("a" % "b" % "c", Nel.of("d"))
    val repoConfig = RepoConfigAlg
      .parseRepoConfig(RepoConfigAlg.configToIgnoreFurtherUpdates(update))
      .getOrElse(RepoConfig())

    repoConfig shouldBe RepoConfig(
      updates = UpdatesConfig(
        ignore = List(UpdatePattern(GroupId("a"), Some("b"), None))
      )
    )
  }

  test("configToIgnoreFurtherUpdates with group update") {
    val update = Update.Group("a" % Nel.of("b", "e") % "c", Nel.of("d"))
    val repoConfig = RepoConfigAlg
      .parseRepoConfig(RepoConfigAlg.configToIgnoreFurtherUpdates(update))
      .getOrElse(RepoConfig())

    repoConfig shouldBe RepoConfig(
      updates = UpdatesConfig(ignore = List(UpdatePattern(GroupId("a"), None, None)))
    )
  }
}
