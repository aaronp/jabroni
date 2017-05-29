package jabroni.rest.test

import cucumber.api.CucumberOptions
import cucumber.api.junit.Cucumber
import org.junit.runner.RunWith

@RunWith(classOf[Cucumber])
@CucumberOptions(
  tags = Array("@Raft"),
  plugin = Array("pretty",
    "html:target/cucumber/raft-report.html",
    "json:target/cucumber/raft-report.json",
    "junit:target/cucumber/raft-report.xml")
)
class RaftTest
