package org.bitcoins.rpc.client.v18

import akka.actor.ActorSystem
import org.bitcoins.commons.jsonmodels.bitcoind.{
  RpcOpts,
  SignRawTransactionResult
}
import org.bitcoins.commons.serializers.JsonSerializers._
import org.bitcoins.commons.serializers.JsonWriters._
import org.bitcoins.core.api.chain.ChainQueryApi
import org.bitcoins.core.api.chain.db.{CompactFilterDb, CompactFilterHeaderDb}
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.script.crypto.HashType
import org.bitcoins.crypto.{DoubleSha256DigestBE, ECPrivateKey}
import org.bitcoins.rpc.client.common.{
  BitcoindRpcClient,
  BitcoindVersion,
  DescriptorRpc,
  PsbtRpc
}
import org.bitcoins.rpc.config.BitcoindInstance
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Try

/** Class for creating a BitcoindV18 instance that can access RPCs
  * @param instance
  * @param actorSystem
  */
class BitcoindV18RpcClient(override val instance: BitcoindInstance)(implicit
    actorSystem: ActorSystem)
    extends BitcoindRpcClient(instance)
    with DescriptorRpc
    with PsbtRpc
    with V18AssortedRpc {

  override lazy val version: Future[BitcoindVersion.V18.type] =
    Future.successful(BitcoindVersion.V18)

  override def getFilterCount(): Future[Int] = filtersUnsupported

  override def getFiltersBetweenHeights(
      startHeight: Int,
      endHeight: Int): Future[Vector[ChainQueryApi.FilterResponse]] =
    filtersUnsupported

  override def getFilterHeaderCount(): Future[Int] = filtersUnsupported

  override def getFilterHeadersAtHeight(
      height: Int): Future[Vector[CompactFilterHeaderDb]] = filtersUnsupported

  override def getBestFilterHeader(): Future[Option[CompactFilterHeaderDb]] =
    filtersUnsupported

  override def getFilterHeader(
      blockHash: DoubleSha256DigestBE): Future[Option[CompactFilterHeaderDb]] =
    filtersUnsupported

  override def getFilter(
      hash: DoubleSha256DigestBE): Future[Option[CompactFilterDb]] =
    filtersUnsupported

  override def getFiltersAtHeight(
      height: Int): Future[Vector[CompactFilterDb]] = filtersUnsupported

  /** $signRawTx
    *
    * This RPC call signs the raw transaction with keys found in
    * the Bitcoin Core wallet.
    */
  def signRawTransactionWithWallet(
      transaction: Transaction,
      utxoDeps: Vector[RpcOpts.SignRawTransactionOutputParameter] =
        Vector.empty,
      sigHash: HashType = HashType.sigHashAll
  ): Future[SignRawTransactionResult] =
    bitcoindCall[SignRawTransactionResult]("signrawtransactionwithwallet",
                                           List(JsString(transaction.hex),
                                                Json.toJson(utxoDeps),
                                                Json.toJson(sigHash)))

  /** $signRawTx
    *
    * This RPC call signs the raw transaction with keys provided
    * manually.
    */
  def signRawTransactionWithKey(
      transaction: Transaction,
      keys: Vector[ECPrivateKey],
      utxoDeps: Vector[RpcOpts.SignRawTransactionOutputParameter] =
        Vector.empty,
      sigHash: HashType = HashType.sigHashAll
  ): Future[SignRawTransactionResult] =
    bitcoindCall[SignRawTransactionResult]("signrawtransactionwithkey",
                                           List(JsString(transaction.hex),
                                                Json.toJson(keys),
                                                Json.toJson(utxoDeps),
                                                Json.toJson(sigHash)))

}

object BitcoindV18RpcClient {

  /** Creates an RPC client from the given instance.
    *
    * Behind the scenes, we create an actor system for
    * you. You can use `withActorSystem` if you want to
    * manually specify an actor system for the RPC client.
    */
  def apply(instance: BitcoindInstance): BitcoindV18RpcClient = {
    implicit val system = ActorSystem.create(BitcoindRpcClient.ActorSystemName)
    withActorSystem(instance)
  }

  /** Creates an RPC client from the given instance,
    * together with the given actor system. This is for
    * advanced users, where you need fine grained control
    * over the RPC client.
    */
  def withActorSystem(instance: BitcoindInstance)(implicit
      system: ActorSystem): BitcoindV18RpcClient =
    new BitcoindV18RpcClient(instance)(system)

  def fromUnknownVersion(
      rpcClient: BitcoindRpcClient): Try[BitcoindV18RpcClient] =
    Try {
      new BitcoindV18RpcClient(rpcClient.instance)(rpcClient.system)
    }

}
