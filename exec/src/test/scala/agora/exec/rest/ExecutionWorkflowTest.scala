package agora.exec.rest

import java.nio.file.Path
import java.util.UUID

import agora.BaseSpec
import agora.api.`match`.MatchDetails
import agora.exec.client.ExecutionClient
import agora.exec.events.{ReceivedJob, SystemEventMonitor}
import agora.exec.model._
import agora.exec.workspace.WorkspaceClient
import agora.io.IterableSubscriber
import agora.rest.test.TestUtils._
import agora.rest.{CommonRequestBuilding, HasMaterializer}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future

class ExecutionWorkflowTest extends BaseSpec with FailFastCirceSupport with HasMaterializer with Eventually with CommonRequestBuilding {

  implicit def richPath(file: Path) = new {
    def trimmedText = file.text.lines.mkString("")
  }

  implicit def richRunProcess(runProcess: RunProcess) = new {
    def asText(resp: HttpResponse): String = {
      val StreamingResult(result) = runProcess.output.streaming.get.asResult(resp)
      val List(dateResult)        = result.toList
      dateResult
    }
  }

  implicit def runnerAsJob(rp: RunProcess): ReceivedJob = {
    ReceivedJob("", None, rp)
  }

  val randomJob = RunProcess("uptime").withWorkspace("ws").withStdOutTo("first.result")

  "CachingWorkflow" should {
    "notify workspaces of file dependencies for results making use of cached results" in {

      /**
        * This test covers the scenario where we run a job which writes down the cached response
        * 'foo'.
        *
        * Given we run a second job which is to produce the result 'bar', but can also use the cached result 'foo',
        * Any jobs which have a dependency on 'bar' should still be triggered when the second job "runs" (I say 'runs',
        * 'cause it won't actually -- it'll just create a symobolic link to the result 'foo')
        */
      withDir { dir =>
        val workspaces = WorkspaceClient(dir, system)

        // create a dependency on 'second.result'
        val secondResultFuture = workspaces.await("ws", Set("second.result"), testTimeout.toMillis)

        val cachingWorkflow = ExecutionWorkflow(Map.empty, workspaces, SystemEventMonitor.DevNull, true)

        val runProcess: RunProcess = randomJob.withCaching(true)

        // call the method under test. The first time the process should execute
        val firstResponse = cachingWorkflow.onExecutionRequest(HttpRequest(), runProcess).futureValue

        // verify first execution/result
        val wsDir = withClue("The first execution should run and cache the result") {
          val text = runProcess.asText(firstResponse)

          // we should get an integer result
          text should not be empty
          val firstResultDir = workspaces.await(runProcess.workspace, Set("first.result"), 0).futureValue

          val outputFile     = firstResultDir.resolve("first.result")
          val outputFileText = outputFile.trimmedText

          outputFileText shouldBe text
          firstResultDir
        }

        withClue("our workspace future should not yet be complete") {
          secondResultFuture.isCompleted shouldBe false
        }

        withClue("the second execution should return the cached result") {

          val secondProcess  = runProcess.withStdOutTo("second.result").useCachedValueWhenAvailable(true)
          val secondResponse = cachingWorkflow.onExecutionRequest(HttpRequest(), secondProcess).futureValue

          // check we get the same result
          secondProcess.asText(secondResponse) shouldBe wsDir.resolve("first.result").trimmedText

          // and second.result exists, and the workspaces are notified
          secondResultFuture.futureValue.resolve("second.result").trimmedText shouldBe wsDir
            .resolve("first.result")
            .trimmedText
        }
      }
    }
    "not return cached results from a previously successful process if enableCacheCheck is not set on the workflow" in {
      withDir { dir =>
        val workspaces = WorkspaceClient(dir, system)

        // we use 'false' to disable cache checks
        val nonCachingWorkflow = ExecutionWorkflow(Map.empty, workspaces, SystemEventMonitor.DevNull, false)

        val runProcess: RunProcess = randomJob.withCaching(true).useCachedValueWhenAvailable(true)

        // execute the job which returns a random result
        val firstResponse = nonCachingWorkflow.onExecutionRequest(HttpRequest(), runProcess).futureValue

        val firstResult = runProcess.asText(firstResponse)

        // execute the same thing again -- caching is switched off though, so we should get a different answer
        // there's a non-zero chance the same random number will be returned a second time, so we put this in
        // an eventually
        eventually {
          val secondResponse = nonCachingWorkflow.onExecutionRequest(HttpRequest(), runProcess).futureValue
          firstResult should not be runProcess.asText(secondResponse)
        }
      }
    }
    "not return cached results from a previously successful process if useCache is not set on the request" in {
      withDir { dir =>
        val workspaces = WorkspaceClient(dir, system)

        val cachingWorkflow = ExecutionWorkflow(Map.empty, workspaces, SystemEventMonitor.DevNull, true)

        val runProcess: RunProcess = randomJob.withCaching(true).useCachedValueWhenAvailable(true)

        // execute the job which returns a random result
        val firstResponse = cachingWorkflow.onExecutionRequest(HttpRequest(), runProcess).futureValue

        val firstResult = runProcess.asText(firstResponse)

        // execute the same thing again -- caching is enabled, but the job's not using it
        eventually {
          val dontCache      = runProcess.useCachedValueWhenAvailable(false)
          val secondResponse = cachingWorkflow.onExecutionRequest(HttpRequest(), dontCache).futureValue
          firstResult should not be runProcess.asText(secondResponse)
        }
      }
    }
    "return the cached error results from a previous process if it exited unsuccessfully" in {

      withDir { dir =>
        val workspaces      = WorkspaceClient(dir, system)
        val cachingWorkflow = ExecutionWorkflow(Map.empty, workspaces, SystemEventMonitor.DevNull, true)

        val throwError: RunProcess = {
          RunProcess("throwError.sh".executable, "7").withCaching(true).useCachedValueWhenAvailable(true)
        }

        // execute the job which returns a random result
        val firstResponse = cachingWorkflow.onExecutionRequest(HttpRequest(), throwError).futureValue

        val exp1 = intercept[ProcessException] {
          throwError.asText(firstResponse)
        }

        exp1.error.exitCode shouldBe Some(7)
        exp1.error.stdErr shouldBe List("first error output", "second error output", "stderr: about to exit with 7")

        val secondResponse = cachingWorkflow.onExecutionRequest(HttpRequest(), throwError).futureValue

        val exp2 = intercept[ProcessException] {
          throwError.asText(secondResponse)
        }
        exp1.error.exitCode shouldBe exp2.error.exitCode
        exp1.error.stdErr shouldBe exp2.error.stdErr
        secondResponse.getHeader("x-cache-out").isPresent shouldBe true
        secondResponse.getHeader("x-cache-err").isPresent shouldBe true
      }
    }
  }

  "ExecutionWorkflow.cancel" should {
    "return false when trying to cancel the same job a second time" in {
      withDir { dir =>
        val client   = WorkspaceClient(dir, system)
        val workflow = ExecutionWorkflow(Map.empty, client, SystemEventMonitor.DevNull)

        val runProcess: RunProcess = {
          RunProcess("yes")
            .withCaching(false)
            .useCachedValueWhenAvailable(false)
            .withStreamingSettings(StreamingSettings())
        }

        val md               = MatchDetails.empty.copy(jobId = "foo")
        val requestWithJobId = HttpRequest().withCommonHeaders(Option(md))

        // start the job ...
        val jobFuture: Future[HttpResponse] = workflow.onExecutionRequest(requestWithJobId, runProcess)

        withClue("We have to start streaming the job output to ensure we don't have a start/cancel race condition") {
          // ... and ensure it's running as to start streaming the results..
          val gotOutputFuture: Future[Boolean] = jobFuture.map { resp =>
            val iter = IterableSubscriber.iterate(resp.entity.dataBytes, 1000)
            iter.hasNext
          }

          gotOutputFuture.futureValue shouldBe true
        }

        // the job is now running, so we call the method under test
        val cancelFuture = workflow.onCancelJob("foo")
        val cancelled    = ExecutionClient.parseCancelResponse(cancelFuture.futureValue).futureValue
        cancelled shouldBe Option(true)

        val cancelled2 = ExecutionClient.parseCancelResponse(workflow.onCancelJob("foo").futureValue).futureValue
        cancelled2 shouldBe None
      }
    }
  }

  "ExecutionWorkflow.onExecutionRequest" should {

    "both stream and write output to a file" in {

      withDir { dir =>
        val client   = WorkspaceClient(dir, system)
        val workflow = ExecutionWorkflow(Map.empty, client, SystemEventMonitor.DevNull)

        val runProcess: RunProcess = RunProcess("echo", "gruess gott").withStdOutTo("std.out")

        // call the method under test
        val future         = workflow.onExecutionRequest(HttpRequest(), runProcess)
        val responseFuture = future.futureValue

        val StreamingResult(result) = runProcess.output.streaming.get.asResult(responseFuture)
        result.toList shouldBe List("gruess gott")

        withClue("the workspace directory should already exist") {
          val wsDir = client.await(runProcess.workspace, Set("std.out"), 0).futureValue
          wsDir.resolve("std.out").text shouldBe "gruess gott\n"
        }
      }
    }

    "return FileResult responses when streaming is not specified" in {
      withDir { dir =>
        val runProcess = RunProcess("echo", "hello world").withStdOutTo("std.out").withoutStreaming()

        val client   = WorkspaceClient(dir, system)
        val workflow = ExecutionWorkflow(Map.empty, client, SystemEventMonitor.DevNull)

        // call the method under test
        val responseFuture = workflow.onExecutionRequest(HttpRequest(), runProcess)

        val fileResult = responseFuture.flatMap(FileResult.apply).futureValue
        fileResult.exitCode shouldBe 0

        withClue("the workspace directory should already exist") {
          val wsDir = client.await(runProcess.workspace, Set("std.out"), 0).futureValue
          wsDir.resolve("std.out").text shouldBe "hello world\n"
        }
      }
    }

    "result in dependencies being triggered after a streaming process is executed" in {
      val httpResp                = verifyDependencies(identity)
      val StreamingResult(output) = StreamingSettings().asResult(httpResp)
      output.mkString(" ").trim shouldBe "hello there world"
    }

    "result in dependencies being triggered after a non-streaming process is executed" in {
      val httpResp = verifyDependencies(_.withoutStreaming)
      val result   = FileResult(httpResp).futureValue
      result.stdOutFile shouldBe None
    }
    "be able to access env variables" in {

      withDir { dir =>
        val runProcess =
          RunProcess("/bin/bash", "-c", "echo FOO is $FOO").withEnv("FOO", "bar").withStdOutTo("foo.txt")

        val client   = WorkspaceClient(dir, system)
        val workflow = ExecutionWorkflow(Map.empty, client, SystemEventMonitor.DevNull)
        // call the method under test
        val responseFuture = workflow.onExecutionRequest(HttpRequest(), runProcess).futureValue

        val StreamingResult(res) = runProcess.output.streaming.get.asResult(responseFuture)
        res.toList shouldBe List("FOO is bar")

        withClue("the workspace directory should already exist") {
          val wsDir = client.await(runProcess.workspace, Set("foo.txt"), 0).futureValue
          wsDir.resolve("foo.txt").text shouldBe "FOO is bar\n"
        }
      }
    }

    "execute a RunProcess with no MatchDetails or streaming" in {
      withDir { dir =>
        val workspaceId = UUID.randomUUID().toString
        val wsDir       = dir.resolve(workspaceId)

        wsDir.resolve("foo").text = "content"

        val arg        = RunProcess("cp", "foo", "bar").withWorkspace(workspaceId).withStdOutTo("std.out").withoutStreaming()
        val workspaces = WorkspaceClient(dir, system)
        val workflow   = ExecutionWorkflow(Map.empty, workspaces, SystemEventMonitor.DevNull)

        // call the method under test
        val httpResponse: HttpResponse = workflow.onExecutionRequest(HttpRequest(), arg).futureValue

        // verify the results
        httpResponse.status.intValue() shouldBe 200

        val response: FileResult = FileResult(httpResponse).futureValue
        response.stdOutFile shouldBe Some("std.out")
        response.workspaceId shouldBe workspaceId
        response.exitCode shouldBe 0

        wsDir.resolve("bar").exists() shouldBe true
        wsDir.resolve("bar").text shouldBe "content"
      }
    }
  }

  def verifyDependencies(f: RunProcess => RunProcess): HttpResponse = {
    withDir { dir =>
      val client   = WorkspaceClient(dir, system)
      val workflow = ExecutionWorkflow(Map.empty, client, SystemEventMonitor.DevNull)

      // one process creates hello.world
      val echoHelloWorld = RunProcess("echo", "hello there world").withStdOutTo("hello.world")

      // and another depends on it... we run the dependency one first (out of order) to exercise
      // the WorkspaceClient
      val catHelloWorld =
        f(RunProcess("cat", "hello.world").withDependencies(echoHelloWorld.workspace, Set("hello.world"), testTimeout))

      // call the method under test
      val catFuture = workflow.onExecutionRequest(HttpRequest(), catHelloWorld)
      catFuture.isCompleted shouldBe false

      // now that we're awaiting a dependency on hello.world, execute the process which will create it
      val echoFuture = workflow.onExecutionRequest(HttpRequest(), echoHelloWorld).futureValue

      // specify a timeout of zero as it should exist
      val workspaceDir = client.await(echoHelloWorld.workspace, Set("hello.world"), 0).futureValue

      workspaceDir.resolve("hello.world").text shouldBe "hello there world\n"

      catFuture.futureValue
    }
  }

}
