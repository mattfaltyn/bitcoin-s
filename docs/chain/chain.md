---
title: Blockchain Verification
id: chain
---

Bitcoin-S comes bundled with a rudimentary blockchain verification
module. This module is currently only released as a library, and not as a binary.
This is because it (nor the documentation) is not deemed production
ready. Use at your own risk, and without too much money depending on it.

## Syncing and verifying block headers

Using the `chain` module of Bitcoin-S it's possible to
sync and verify block headers from the Bitcoin blockchain. In this document
we demonstrate how to do this, while persisting it to disk. We should be
able to read this chain on subsequent runs, assuming we are connected
to the same `bitcoind` instance.

```scala mdoc:invisible
import org.bitcoins.chain.blockchain._
import org.bitcoins.chain.blockchain.sync._
import org.bitcoins.chain.models._
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.rpc.config.BitcoindInstanceLocal
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.chain._
import akka.actor.ActorSystem
import scala.concurrent._
import java.nio.file.Files
```

```scala mdoc:compile-only

implicit val ec = ExecutionContext.global
implicit val system = ActorSystem("System")
// We are assuming that a `bitcoind` regtest node is running the background.
// You can see our `bitcoind` guides to see how to connect
// to a local or remote `bitcoind` node.

val bitcoindInstance = BitcoindInstanceLocal.fromDatadir()
val rpcCli = BitcoindRpcClient(bitcoindInstance)

// Next, we need to create a way to monitor the chain:

val getBestBlockHash = SyncUtil.getBestBlockHashFunc(rpcCli)

val getBlockHeader = SyncUtil.getBlockHeaderFunc(rpcCli)

// set a data directory
val datadir = Files.createTempDirectory("bitcoin-s-test")

// set the current network to regtest
import com.typesafe.config.ConfigFactory
val config = ConfigFactory.parseString {
    """
    | bitcoin-s {
    |   network = regtest
    | }
    |""".stripMargin
}

implicit val chainConfig = ChainAppConfig(datadir, Vector(config))

// Initialize the needed database tables if they don't exist:
val chainProjectInitF = chainConfig.start()
val blockHeaderDAO = BlockHeaderDAO()
val compactFilterHeaderDAO = CompactFilterHeaderDAO()
val compactFilterDAO = CompactFilterDAO()


//initialize the chain handler from the database
val chainHandler = ChainHandler.fromDatabase(blockHeaderDAO, compactFilterHeaderDAO, compactFilterDAO)

// Now, do the actual syncing:
val syncedChainApiF = for {
    _ <- chainProjectInitF
    synced <- ChainSync.sync(chainHandler, getBlockHeader, getBestBlockHash)
} yield synced

val syncResultF = syncedChainApiF.flatMap { chainApi =>
  chainApi.getBlockCount().map(count => println(s"chain api blockcount=${count}"))

  rpcCli.getBlockCount.map(count => println(s"bitcoind blockcount=${count}"))
}

syncResultF.onComplete { case result =>
  println(s"Sync result=${result}")
}
```
