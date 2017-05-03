package jabroni.rest

import com.typesafe.config.{Config, ConfigFactory}

/**
  * A parsed configuration for our jabroni app
  */
class ServerConfig(override val config: Config) extends BaseConfig {

  val host = config.getString("host")
  val port = config.getInt("port")
  val launchBrowser = config.getBoolean("launchBrowser")
  val waitOnUserInput = config.getBoolean("waitOnUserInput")
}

object ServerConfig {
  def defaultConfig(path : String) = ConfigFactory.load().getConfig(path)

  def apply(conf: Config): ServerConfig = new ServerConfig(conf)
}
