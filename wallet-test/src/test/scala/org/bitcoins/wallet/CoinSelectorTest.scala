package org.bitcoins.wallet

import org.bitcoins.core.api.wallet.CoinSelector
import org.bitcoins.core.api.wallet.db.{SegwitV0SpendingInfo, SpendingInfoDb}
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.wallet.fee.{FeeUnit, SatoshisPerByte}
import org.bitcoins.core.wallet.utxo.TxoState
import org.bitcoins.testkit.wallet.{BitcoinSWalletTest, WalletTestUtil}
import org.bitcoins.testkitcore.Implicits._
import org.bitcoins.testkitcore.gen.{TransactionGenerators, WitnessGenerators}
import org.scalatest.FutureOutcome

class CoinSelectorTest extends BitcoinSWalletTest {

  case class CoinSelectionFixture(
      output: TransactionOutput,
      feeRate: FeeUnit,
      utxo1: SpendingInfoDb,
      utxo2: SpendingInfoDb,
      utxo3: SpendingInfoDb) {
    val utxoSet: Vector[SpendingInfoDb] = Vector(utxo1, utxo2, utxo3)
  }

  override type FixtureParam = CoinSelectionFixture

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    val output = TransactionOutput(99.sats, ScriptPubKey.empty)
    val feeRate = SatoshisPerByte(CurrencyUnits.zero)

    val outpoint1 = TransactionGenerators.outPoint.sampleSome
    val utxo1 = SegwitV0SpendingInfo(
      txid = outpoint1.txIdBE,
      state = TxoState.DoesNotExist,
      id = Some(1),
      outPoint = outpoint1,
      output = TransactionOutput(10.sats, ScriptPubKey.empty),
      privKeyPath = WalletTestUtil.sampleSegwitPath,
      scriptWitness = WitnessGenerators.scriptWitness.sampleSome,
      spendingTxIdOpt = None
    )
    val outPoint2 = TransactionGenerators.outPoint.sampleSome
    val utxo2 = SegwitV0SpendingInfo(
      txid = outPoint2.txIdBE,
      state = TxoState.DoesNotExist,
      id = Some(2),
      outPoint = outPoint2,
      output = TransactionOutput(90.sats, ScriptPubKey.empty),
      privKeyPath = WalletTestUtil.sampleSegwitPath,
      scriptWitness = WitnessGenerators.scriptWitness.sampleSome,
      spendingTxIdOpt = None
    )

    val outPoint3 = TransactionGenerators.outPoint.sampleSome
    val utxo3 = SegwitV0SpendingInfo(
      txid = outPoint3.txIdBE,
      state = TxoState.DoesNotExist,
      id = Some(3),
      outPoint = outPoint3,
      output = TransactionOutput(20.sats, ScriptPubKey.empty),
      privKeyPath = WalletTestUtil.sampleSegwitPath,
      scriptWitness = WitnessGenerators.scriptWitness.sampleSome,
      spendingTxIdOpt = None
    )

    test(CoinSelectionFixture(output, feeRate, utxo1, utxo2, utxo3))
  }

  behavior of "CoinSelector"

  it must "accumulate largest outputs" in { fixture =>
    val selection =
      CoinSelector.accumulateLargest(walletUtxos = fixture.utxoSet,
                                     outputs = Vector(fixture.output),
                                     feeRate = fixture.feeRate)

    assert(selection == Vector(fixture.utxo2, fixture.utxo3))
  }

  it must "accumulate smallest outputs" in { fixture =>
    val selection =
      CoinSelector.accumulateSmallestViable(walletUtxos = fixture.utxoSet,
                                            outputs = Vector(fixture.output),
                                            feeRate = fixture.feeRate)

    assert(selection == Vector(fixture.utxo1, fixture.utxo3, fixture.utxo2))
  }

  it must "accumulate outputs in order" in { fixture =>
    val selection = CoinSelector.accumulate(walletUtxos = fixture.utxoSet,
                                            outputs = Vector(fixture.output),
                                            feeRate = fixture.feeRate)

    assert(selection == Vector(fixture.utxo1, fixture.utxo2))
  }

  it must "accumulate random outputs" in { fixture =>
    val first = CoinSelector.randomSelection(walletUtxos = fixture.utxoSet,
                                             outputs = Vector(fixture.output),
                                             feeRate = fixture.feeRate)

    val selections = Vector.fill(20)(
      CoinSelector.randomSelection(walletUtxos = fixture.utxoSet,
                                   outputs = Vector(fixture.output),
                                   feeRate = fixture.feeRate))

    // it should not get the same thing every time
    assert(selections.exists(_ != first))
  }

  it must "select the least wasteful outputs" in { fixture =>
    val selection =
      CoinSelector.selectByLeastWaste(walletUtxos = fixture.utxoSet,
                                      outputs = Vector(fixture.output),
                                      feeRate = fixture.feeRate,
                                      longTermFeeRate =
                                        SatoshisPerByte.fromLong(10))

    // Need to sort as ordering will be different sometimes
    val sortedSelection = selection.sortBy(_.outPoint.hex)
    val sortedExpected =
      Vector(fixture.utxo2, fixture.utxo1, fixture.utxo3).sortBy(_.outPoint.hex)

    assert(sortedSelection == sortedExpected)
  }

  it must "correctly approximate transaction input size" in { fixture =>
    val expected1 =
      32 + 4 + 1 + 4 + fixture.utxo1.scriptWitnessOpt.get.bytes.length
    val expected2 =
      32 + 4 + 1 + 4 + fixture.utxo2.scriptWitnessOpt.get.bytes.length
    val expected3 =
      32 + 4 + 1 + 4 + fixture.utxo3.scriptWitnessOpt.get.bytes.length

    assert(CoinSelector.approximateUtxoSize(fixture.utxo1) == expected1)
    assert(CoinSelector.approximateUtxoSize(fixture.utxo2) == expected2)
    assert(CoinSelector.approximateUtxoSize(fixture.utxo3) == expected3)
  }
}
