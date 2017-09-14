package agora.exec.client

import agora.api.BaseSpec
import agora.exec.model.{ProcessException, RunProcess}
import agora.rest.test.TestUtils._

/**
  * Any runner should adhere to these tests
  */
trait ProcessRunnerTCK { self: BaseSpec =>

  def runner: ProcessRunner

  "ProcessRunner" should {

    "stream results" in {
      val firstResults = runner.stream("bigOutput.sh".executable, srcDir.toString, "2").futureValue
      val all          = firstResults.toList
      all.size should be > 10
    }
    "return the error output when the worker returns a specified error code" in {
      val exp = intercept[ProcessException] {
        val firstResults = runner.stream("throwError.sh".executable, "123").futureValue

        // try and consume the streamed output
        firstResults.toList
      }
      exp.error.exitCode shouldBe Option(123)
      exp.error.process.command shouldBe List("throwError.sh".executable, "123")
      exp.error.stdErr shouldBe List("first error output", "second error output", "stderr: about to exit with 123")
    }

    "be able to specify acceptable return codes" in {
      // like the above test, but doesn't include the stderr as '123' is our success code
      val firstResults = runner.run(RunProcess(List("throwError.sh".executable, "123"), successExitCodes = Set(123))).futureValue
      firstResults.toList shouldBe List("first info output", "second info output", "stdout: about to exit with 123")
    }

    "run simple commands remotely" in {
      val res: Iterator[String] = runner.stream("echo", "testing 123").futureValue
      res.mkString(" ") shouldBe "testing 123"
    }
    "run commands which operate on environment variables" in {
      val res: Iterator[String] = runner.run(RunProcess("bash", "-c", "echo FOO is $FOO").withEnv("FOO", "bar")).futureValue
      res.mkString("\n") shouldBe "FOO is bar"
    }
  }
}