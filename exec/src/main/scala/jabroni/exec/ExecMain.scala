package jabroni.exec

import com.typesafe.scalalogging.StrictLogging

object ExecMain extends StrictLogging {
  def main(args: Array[String]): Unit = {
    val conf = ExecConfig(args)
    logger.trace(conf.describe)
    conf.start()
  }
}
