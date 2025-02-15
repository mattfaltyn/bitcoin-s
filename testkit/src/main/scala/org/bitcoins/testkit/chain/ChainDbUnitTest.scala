package org.bitcoins.testkit.chain

import com.typesafe.config.ConfigFactory
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig.ProjectType
import org.bitcoins.testkit.{BitcoinSTestAppConfig, EmbeddedPg}

trait ChainDbUnitTest extends ChainUnitTest with EmbeddedPg {

  implicit override lazy val cachedChainConf: ChainAppConfig = {
    val memoryDb =
      BitcoinSTestAppConfig.configWithEmbeddedDb(Some(ProjectType.Chain), pgUrl)
    val chainConfig: ChainAppConfig =
      BitcoinSTestAppConfig.getSpvTestConfig().chainConf
    chainConfig.withOverrides(memoryDb)
  }

  override lazy val mainnetAppConfig: ChainAppConfig = {
    val memoryDb =
      BitcoinSTestAppConfig.configWithEmbeddedDb(Some(ProjectType.Chain), pgUrl)
    val mainnetConf = ConfigFactory.parseString("bitcoin-s.network = mainnet")
    val chainConfig: ChainAppConfig =
      BitcoinSTestAppConfig.getSpvTestConfig(mainnetConf).chainConf
    chainConfig.withOverrides(memoryDb)
  }

  override def afterAll(): Unit = {
    super[EmbeddedPg].afterAll()
    super[ChainUnitTest].afterAll()
  }
}
