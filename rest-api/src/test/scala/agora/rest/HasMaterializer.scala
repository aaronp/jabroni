package agora.rest

import agora.rest.HasMaterializer.{getClass, systemConf}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext
import scala.util.Try

trait HasMaterializer extends BeforeAndAfterAll { this: org.scalatest.BeforeAndAfterAll with org.scalatest.Suite =>

  implicit def execContext: ExecutionContext = materializer.executionContext

//  implicit def system: ActorSystem = HasMaterializer.testSystem

//  implicit def materializer: Materializer = HasMaterializer.materializer

  implicit lazy val system: ActorSystem = {
    ActorSystem(getClass.getSimpleName.filter(_.isLetter), HasMaterializer.systemConf).ensuring(_.settings.Daemonicity)
  }
  implicit lazy val materializer: ActorMaterializer = {
    ActorMaterializer()(system)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
  }
}

object HasMaterializer {

  lazy val systemConf = ConfigFactory.load("test-system")
//  private[this] def systemConf = ConfigFactory.load("test-system")
//
//  private lazy val testSystem: ActorSystem = {
//    ActorSystem(getClass.getSimpleName.filter(_.isLetter), systemConf).ensuring(_.settings.Daemonicity)
//  }
//  private lazy val materializer: ActorMaterializer = {
//    ActorMaterializer()(testSystem)
//  }
}
