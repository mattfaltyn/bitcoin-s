package org.bitcoins.core.wallet.utxo

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.number.UInt64
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.crypto.HashType
import org.bitcoins.core.util.{BitcoinScriptUtil, BytesUtil}
import org.bitcoins.crypto.{
  ECPublicKey,
  ECPublicKeyBytes,
  LowRDummyECDigitalSignature,
  NetworkElement,
  Sign
}

import scala.annotation.tailrec

/** An InputInfo contains all information other than private keys about
  * a particular spending condition in a UTXO.
  *
  * Note that while some pieces of information (TxOutPoint, amount, etc.)
  * apply to all input types, other pieces are specific to particular ones
  * such as a witness to a SegWit input.
  */
sealed trait InputInfo {
  def outPoint: TransactionOutPoint

  def amount: CurrencyUnit

  def scriptPubKey: ScriptPubKey

  def output: TransactionOutput = {
    TransactionOutput(amount, scriptPubKey)
  }

  def outputReference: OutputReference = {
    OutputReference(outPoint, output)
  }

  def conditionalPath: ConditionalPath

  def pubKeys: Vector[ECPublicKey]

  def requiredSigs: Int

  def toSpendingInfo(
      prevTransaction: Transaction,
      signers: Vector[Sign],
      hashType: HashType): ScriptSignatureParams[InputInfo] = {
    ScriptSignatureParams(this, prevTransaction, signers, hashType)
  }

  def toSpendingInfo(
      prevTransaction: Transaction,
      signer: Sign,
      hashType: HashType): ECSignatureParams[InputInfo] = {
    ECSignatureParams(this, prevTransaction, signer, hashType)
  }

  def genericWithSignFrom(
      signerMaterial: InputSigningInfo[InputInfo]): InputSigningInfo[
    this.type] = {
    signerMaterial match {
      case info: ScriptSignatureParams[InputInfo] => withSignFrom(info)
      case info: ECSignatureParams[InputInfo]     => withSignFrom(info)
    }
  }

  def withSignFrom(
      signerMaterial: ScriptSignatureParams[InputInfo]): ScriptSignatureParams[
    this.type] = {
    signerMaterial.copy(inputInfo = this)
  }

  def withSignFrom(
      signerMaterial: ECSignatureParams[InputInfo]): ECSignatureParams[
    this.type] = {
    signerMaterial.copy(inputInfo = this)
  }
}

object InputInfo {

  def getRedeemScript(inputInfo: InputInfo): Option[ScriptPubKey] = {
    inputInfo match {
      case _: RawInputInfo | _: SegwitV0NativeInputInfo |
          _: UnassignedSegwitNativeInputInfo =>
        None
      case info: P2SHInputInfo => Some(info.redeemScript)
    }
  }

  def getScriptWitness(inputInfo: InputInfo): Option[ScriptWitness] = {
    inputInfo match {
      case _: RawInputInfo | _: P2SHNonSegwitInputInfo => None
      case info: SegwitV0NativeInputInfo               => Some(info.scriptWitness)
      case info: P2SHNestedSegwitV0InputInfo           => Some(info.scriptWitness)
      case info: UnassignedSegwitNativeInputInfo       => Some(info.scriptWitness)
    }
  }

  def getHashPreImages(inputInfo: InputInfo): Vector[NetworkElement] = {
    inputInfo match {
      case info: P2WSHV0InputInfo     => info.hashPreImages
      case info: P2SHInputInfo        => info.hashPreImages
      case info: P2PKHInputInfo       => Vector(info.pubKey)
      case info: LockTimeInputInfo    => info.hashPreImages
      case info: ConditionalInputInfo => info.hashPreImages
      case _: UnassignedSegwitNativeInputInfo | _: EmptyInputInfo |
          _: P2PKInputInfo | _: P2PKWithTimeoutInputInfo |
          _: MultiSignatureInputInfo | _: P2WPKHV0InputInfo =>
        Vector.empty
    }
  }

  def getPKHPreImage(inputInfo: InputInfo): Option[ECPublicKey] = {
    getHashPreImages(inputInfo).collectFirst { case pubKey: ECPublicKey =>
      pubKey
    }
  }

  /** Returns the needed hash pre-images and conditional path that was used to spend the input
    * at inputIndex, this is calculated through the ScriptSignature and ScriptWitness
    */
  def getHashPreImagesAndConditionalPath(
      signedTransaction: Transaction,
      inputIndex: Int): (Vector[NetworkElement], ConditionalPath) = {

    val txIn = signedTransaction.inputs(inputIndex)

    @tailrec
    def getPreImagesAndCondPath(
        scriptSignature: ScriptSignature,
        conditionalPath: Vector[Boolean] = Vector.empty): (
        Vector[NetworkElement],
        Vector[Boolean]) = {
      scriptSignature match {
        case p2pkh: P2PKHScriptSignature =>
          (Vector(p2pkh.publicKey), conditionalPath)
        case cond: ConditionalScriptSignature =>
          val path = conditionalPath :+ cond.isTrue
          getPreImagesAndCondPath(cond.nestedScriptSig, path)
        case p2sh: P2SHScriptSignature =>
          getPreImagesAndCondPath(p2sh.scriptSignatureNoRedeemScript,
                                  conditionalPath)
        case _: ScriptSignature =>
          (Vector.empty, conditionalPath)
      }
    }

    val (preImages, conditionsVec) = signedTransaction match {
      case EmptyTransaction =>
        (Vector.empty, Vector.empty)
      case _: BaseTransaction =>
        getPreImagesAndCondPath(txIn.scriptSignature)
      case wtx: WitnessTransaction =>
        wtx.witness.witnesses(inputIndex) match {
          case p2wpkh: P2WPKHWitnessV0 =>
            (Vector(p2wpkh.pubKey), Vector.empty)
          case p2wsh: P2WSHWitnessV0 =>
            getPreImagesAndCondPath(p2wsh.scriptSignature)
          case EmptyScriptWitness =>
            getPreImagesAndCondPath(txIn.scriptSignature)
        }
    }

    val conditionalPath = ConditionalPath.fromBranch(conditionsVec)

    (preImages, conditionalPath)
  }

  /** Returns the maximum byteSize of any resulting ScriptSignature */
  def maxScriptSigLen(info: InputInfo): Int = {
    val asmByteSize =
      maxScriptSigLenAndStackHeight(info, forP2WSH = false).scriptSigLen
    val varIntSize = CompactSizeUInt(UInt64(asmByteSize)).byteSize

    asmByteSize + varIntSize.toInt
  }

  case class ScriptSigLenAndStackHeight(scriptSigLen: Int, stackHeight: Int)

  /** Computes the byteSize of witness/scriptSignature for the given info,
    * and also returns the number of stack elements for the P2WSH case.
    */
  private def maxScriptSigLenAndStackHeight(
      info: InputInfo,
      forP2WSH: Boolean): ScriptSigLenAndStackHeight = {
    val boolSize = if (forP2WSH) 2 else 1

    info match {
      case _: SegwitV0NativeInputInfo | _: UnassignedSegwitNativeInputInfo =>
        ScriptSigLenAndStackHeight(0, 0)
      case info: P2SHInputInfo =>
        val serializedRedeemScript = ScriptConstant(info.redeemScript.asmBytes)
        val pushOps = BitcoinScriptUtil.calculatePushOp(serializedRedeemScript)
        val redeemScriptLen =
          BytesUtil
            .toByteVector(pushOps.:+(serializedRedeemScript))
            .length
            .toInt
        val ScriptSigLenAndStackHeight(scriptSigLen, stackHeight) =
          maxScriptSigLenAndStackHeight(info.nestedInputInfo, forP2WSH)

        ScriptSigLenAndStackHeight(redeemScriptLen + scriptSigLen,
                                   stackHeight + 1)
      case _: EmptyInputInfo =>
        ScriptSigLenAndStackHeight(boolSize, 1)
      case _: P2PKInputInfo =>
        ScriptSigLenAndStackHeight(
          P2PKScriptSignature(
            LowRDummyECDigitalSignature).asmBytes.length.toInt,
          1)
      case _: P2PKHInputInfo =>
        ScriptSigLenAndStackHeight(
          P2PKHScriptSignature(LowRDummyECDigitalSignature,
                               ECPublicKey.dummy).asmBytes.length.toInt,
          2)
      case info: P2PKWithTimeoutInputInfo =>
        ScriptSigLenAndStackHeight(
          P2PKWithTimeoutScriptSignature(
            info.isBeforeTimeout,
            LowRDummyECDigitalSignature).asmBytes.length.toInt,
          2)
      case info: MultiSignatureInputInfo =>
        ScriptSigLenAndStackHeight(
          MultiSignatureScriptSignature(
            Vector.fill(info.requiredSigs)(
              LowRDummyECDigitalSignature)).asmBytes.length.toInt,
          1 + info.requiredSigs)
      case info: ConditionalInputInfo =>
        val ScriptSigLenAndStackHeight(maxLen, stackHeight) =
          maxScriptSigLenAndStackHeight(info.nestedInputInfo, forP2WSH)
        ScriptSigLenAndStackHeight(maxLen + boolSize, stackHeight + 1)
      case info: LockTimeInputInfo =>
        maxScriptSigLenAndStackHeight(info.nestedInputInfo, forP2WSH)
    }
  }

  /** Returns the maximum byteSize of any resulting ScriptWitness */
  @tailrec
  def maxWitnessLen(info: InputInfo): Int = {
    info match {
      case _: RawInputInfo | _: P2SHNonSegwitInputInfo => 0
      case _: P2WPKHV0InputInfo                        => 107
      case info: P2WSHV0InputInfo =>
        val ScriptSigLenAndStackHeight(scriptSigLen, stackHeight) =
          maxScriptSigLenAndStackHeight(info.nestedInputInfo, forP2WSH = true)
        val stackHeightByteSize = CompactSizeUInt(UInt64(stackHeight)).byteSize
        val redeemScriptSize = info.scriptWitness.redeemScript.byteSize

        (stackHeightByteSize + redeemScriptSize + scriptSigLen).toInt
      case info: P2SHNestedSegwitV0InputInfo =>
        maxWitnessLen(info.nestedInputInfo)
      case _: UnassignedSegwitNativeInputInfo =>
        throw new IllegalArgumentException(
          s"Cannot compute witness for unknown segwit InputInfo, got $info")
    }
  }

  def apply(
      outPoint: TransactionOutPoint,
      output: TransactionOutput,
      redeemScriptOpt: Option[ScriptPubKey],
      scriptWitnessOpt: Option[ScriptWitness],
      conditionalPath: ConditionalPath,
      hashPreImages: Vector[NetworkElement] = Vector.empty): InputInfo = {
    output.scriptPubKey match {
      case _: P2SHScriptPubKey =>
        redeemScriptOpt match {
          case None =>
            throw new IllegalArgumentException(
              "Redeem Script must be defined for P2SH.")
          case Some(redeemScript) =>
            redeemScript match {
              case _: WitnessScriptPubKeyV0 =>
                val witness = scriptWitnessOpt match {
                  case Some(witness: ScriptWitnessV0) => witness
                  case None =>
                    throw new IllegalArgumentException(
                      "Script Witness must be defined for (nested) Segwit input")
                  case Some(_: ScriptWitness) =>
                    throw new UnsupportedOperationException(
                      "Only v0 Segwit is currently supported")
                }
                P2SHNestedSegwitV0InputInfo(outPoint,
                                            output.value,
                                            witness,
                                            conditionalPath,
                                            hashPreImages)
              case nonWitnessSPK: RawScriptPubKey =>
                P2SHNonSegwitInputInfo(outPoint,
                                       output.value,
                                       nonWitnessSPK,
                                       conditionalPath,
                                       hashPreImages)
              case _: P2SHScriptPubKey =>
                throw new IllegalArgumentException("Cannot have nested P2SH")
              case _: WitnessScriptPubKeyV1 =>
                throw new UnsupportedOperationException(
                  s"Unsupported Taproot ScriptPubKey ${output.scriptPubKey}")
              case _: UnassignedWitnessScriptPubKey =>
                throw new UnsupportedOperationException(
                  s"Unsupported ScriptPubKey ${output.scriptPubKey}")
            }
        }
      case _: WitnessScriptPubKeyV0 =>
        val witness = scriptWitnessOpt match {
          case Some(witness: ScriptWitnessV0) => witness
          case None =>
            throw new IllegalArgumentException(
              "Script Witness must be defined for Segwit input")
          case Some(_: ScriptWitness) =>
            throw new UnsupportedOperationException(
              "Only v0 Segwit is currently supported")
        }
        SegwitV0NativeInputInfo(outPoint,
                                output.value,
                                witness,
                                conditionalPath,
                                hashPreImages)
      case wspk @ (_: UnassignedWitnessScriptPubKey |
          _: WitnessScriptPubKeyV1) =>
        UnassignedSegwitNativeInputInfo(
          outPoint,
          output.value,
          wspk.asInstanceOf[WitnessScriptPubKey],
          scriptWitnessOpt.getOrElse(EmptyScriptWitness),
          conditionalPath,
          Vector.empty)
      case rawSPK: RawScriptPubKey =>
        RawInputInfo(outPoint,
                     output.value,
                     rawSPK,
                     conditionalPath,
                     hashPreImages)
    }
  }
}

sealed trait RawInputInfo extends InputInfo {
  override def scriptPubKey: RawScriptPubKey
}

object RawInputInfo {

  def apply(
      outPoint: TransactionOutPoint,
      amount: CurrencyUnit,
      scriptPubKey: RawScriptPubKey,
      conditionalPath: ConditionalPath,
      hashPreImages: Vector[NetworkElement] = Vector.empty): RawInputInfo = {
    scriptPubKey match {
      case p2pk: P2PKScriptPubKey =>
        P2PKInputInfo(outPoint, amount, p2pk)
      case p2pkh: P2PKHScriptPubKey =>
        hashPreImages.collectFirst {
          case pubKey: ECPublicKey =>
            pubKey
          case pubKeyBytes: ECPublicKeyBytes => pubKeyBytes.toPublicKey
        } match {
          case None =>
            throw new IllegalArgumentException(
              s"P2PKH pre-image must be specified for P2PKH ScriptPubKey, got $hashPreImages")
          case Some(p2pkhPreImage) =>
            require(
              P2PKHScriptPubKey(p2pkhPreImage) == p2pkh,
              s"Specified P2PKH pre-image ($p2pkhPreImage) does not match $p2pkh")

            P2PKHInputInfo(outPoint, amount, p2pkhPreImage)
        }
      case p2pkWithTimeout: P2PKWithTimeoutScriptPubKey =>
        conditionalPath.headOption match {
          case None =>
            throw new IllegalArgumentException(
              "ConditionalPath must be specified for P2PKWithTimeout")
          case Some(beforeTimeout) =>
            P2PKWithTimeoutInputInfo(outPoint,
                                     amount,
                                     p2pkWithTimeout,
                                     beforeTimeout)
        }
      case multiSig: MultiSignatureScriptPubKey =>
        MultiSignatureInputInfo(outPoint, amount, multiSig)
      case conditional: ConditionalScriptPubKey =>
        ConditionalInputInfo(outPoint,
                             amount,
                             conditional,
                             conditionalPath,
                             hashPreImages)
      case lockTime: LockTimeScriptPubKey =>
        LockTimeInputInfo(outPoint,
                          amount,
                          lockTime,
                          conditionalPath,
                          hashPreImages)
      case EmptyScriptPubKey =>
        EmptyInputInfo(outPoint, amount)
      case _: NonStandardScriptPubKey | _: WitnessCommitment =>
        throw new UnsupportedOperationException(
          s"Currently unsupported ScriptPubKey $scriptPubKey")
    }
  }
}

case class EmptyInputInfo(outPoint: TransactionOutPoint, amount: CurrencyUnit)
    extends RawInputInfo {
  override def scriptPubKey: EmptyScriptPubKey.type = EmptyScriptPubKey

  override def conditionalPath: ConditionalPath =
    ConditionalPath.NoCondition
  override def pubKeys: Vector[ECPublicKey] = Vector.empty
  override def requiredSigs: Int = 0
}

case class P2PKInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptPubKey: P2PKScriptPubKey)
    extends RawInputInfo {

  override def conditionalPath: ConditionalPath =
    ConditionalPath.NoCondition

  override def pubKeys: Vector[ECPublicKey] = Vector(
    scriptPubKey.publicKey.toPublicKey)

  override def requiredSigs: Int = 1
}

case class P2PKHInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    pubKey: ECPublicKey)
    extends RawInputInfo {
  override def scriptPubKey: P2PKHScriptPubKey = P2PKHScriptPubKey(pubKey)

  override def conditionalPath: ConditionalPath =
    ConditionalPath.NoCondition

  override def pubKeys: Vector[ECPublicKey] = Vector(pubKey)

  override def requiredSigs: Int = 1
}

case class P2PKWithTimeoutInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptPubKey: P2PKWithTimeoutScriptPubKey,
    isBeforeTimeout: Boolean)
    extends RawInputInfo {

  override def conditionalPath: ConditionalPath = {
    if (isBeforeTimeout) {
      ConditionalPath.nonNestedTrue
    } else {
      ConditionalPath.nonNestedFalse
    }
  }

  override def pubKeys: Vector[ECPublicKey] =
    Vector(scriptPubKey.pubKey.toPublicKey,
           scriptPubKey.timeoutPubKey.toPublicKey)

  override def requiredSigs: Int = 1
}

case class MultiSignatureInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptPubKey: MultiSignatureScriptPubKey)
    extends RawInputInfo {

  override def conditionalPath: ConditionalPath =
    ConditionalPath.NoCondition

  override def pubKeys: Vector[ECPublicKey] =
    scriptPubKey.publicKeys.map(_.toPublicKey).toVector

  override def requiredSigs: Int = scriptPubKey.requiredSigs
}

case class ConditionalInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptPubKey: ConditionalScriptPubKey,
    conditionalPath: ConditionalPath,
    hashPreImages: Vector[NetworkElement] = Vector.empty)
    extends RawInputInfo {

  lazy val (condition: Boolean, nextConditionalPath: ConditionalPath) =
    conditionalPath match {
      case ConditionalPath.ConditionTrue(nextCondition) =>
        (true, nextCondition)
      case ConditionalPath.ConditionFalse(nextCondition) =>
        (false, nextCondition)
      case ConditionalPath.NoCondition =>
        throw new IllegalArgumentException("Must specify True or False")
    }

  val nestedInputInfo: RawInputInfo = {
    val nestedSPK = if (condition) {
      scriptPubKey.trueSPK
    } else {
      scriptPubKey.falseSPK
    }

    RawInputInfo(outPoint,
                 amount,
                 nestedSPK,
                 nextConditionalPath,
                 hashPreImages)
  }

  override def pubKeys: Vector[ECPublicKey] = nestedInputInfo.pubKeys

  override def requiredSigs: Int = nestedInputInfo.requiredSigs
}

case class LockTimeInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptPubKey: LockTimeScriptPubKey,
    conditionalPath: ConditionalPath,
    hashPreImages: Vector[NetworkElement] = Vector.empty
) extends RawInputInfo {

  val nestedInputInfo: RawInputInfo = RawInputInfo(
    outPoint,
    amount,
    scriptPubKey.nestedScriptPubKey,
    conditionalPath,
    hashPreImages)

  override def pubKeys: Vector[ECPublicKey] = nestedInputInfo.pubKeys

  override def requiredSigs: Int = nestedInputInfo.requiredSigs
}

sealed trait SegwitV0NativeInputInfo extends InputInfo {
  def scriptWitness: ScriptWitnessV0
}

object SegwitV0NativeInputInfo {

  def apply(
      outPoint: TransactionOutPoint,
      amount: CurrencyUnit,
      scriptWitness: ScriptWitnessV0,
      conditionalPath: ConditionalPath,
      hashPreImages: Vector[NetworkElement] =
        Vector.empty): SegwitV0NativeInputInfo = {
    scriptWitness match {
      case p2wpkh: P2WPKHWitnessV0 =>
        P2WPKHV0InputInfo(outPoint, amount, p2wpkh.pubKey.toPublicKey)
      case p2wsh: P2WSHWitnessV0 =>
        P2WSHV0InputInfo(outPoint,
                         amount,
                         p2wsh,
                         conditionalPath,
                         hashPreImages)
    }
  }
}

case class P2WPKHV0InputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    pubKey: ECPublicKey)
    extends SegwitV0NativeInputInfo {
  override def scriptPubKey: P2WPKHWitnessSPKV0 = P2WPKHWitnessSPKV0(pubKey)

  override def scriptWitness: P2WPKHWitnessV0 = P2WPKHWitnessV0(pubKey)

  override def conditionalPath: ConditionalPath =
    ConditionalPath.NoCondition

  override def pubKeys: Vector[ECPublicKey] = Vector(pubKey)

  override def requiredSigs: Int = 1
}

case class P2WSHV0InputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptWitness: P2WSHWitnessV0,
    conditionalPath: ConditionalPath,
    hashPreImages: Vector[NetworkElement] = Vector.empty)
    extends SegwitV0NativeInputInfo {

  override def scriptPubKey: P2WSHWitnessSPKV0 =
    P2WSHWitnessSPKV0(scriptWitness.redeemScript)

  val nestedInputInfo: RawInputInfo =
    RawInputInfo(outPoint,
                 amount,
                 scriptWitness.redeemScript,
                 conditionalPath,
                 hashPreImages)

  override def pubKeys: Vector[ECPublicKey] = nestedInputInfo.pubKeys

  override def requiredSigs: Int = nestedInputInfo.requiredSigs
}

case class UnassignedSegwitNativeInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptPubKey: WitnessScriptPubKey,
    scriptWitness: ScriptWitness,
    conditionalPath: ConditionalPath,
    pubKeys: Vector[ECPublicKey])
    extends InputInfo {
  override def requiredSigs: Int = pubKeys.length
}

sealed trait P2SHInputInfo extends InputInfo {
  def hashPreImages: Vector[NetworkElement]

  def redeemScript: ScriptPubKey

  override def scriptPubKey: P2SHScriptPubKey = P2SHScriptPubKey(redeemScript)

  def nestedInputInfo: InputInfo

  override def pubKeys: Vector[ECPublicKey] = nestedInputInfo.pubKeys

  override def requiredSigs: Int = nestedInputInfo.requiredSigs
}

case class P2SHNonSegwitInputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    redeemScript: RawScriptPubKey,
    conditionalPath: ConditionalPath,
    hashPreImages: Vector[NetworkElement] = Vector.empty)
    extends P2SHInputInfo {

  override val nestedInputInfo: RawInputInfo =
    RawInputInfo(outPoint, amount, redeemScript, conditionalPath, hashPreImages)
}

case class P2SHNestedSegwitV0InputInfo(
    outPoint: TransactionOutPoint,
    amount: CurrencyUnit,
    scriptWitness: ScriptWitnessV0,
    conditionalPath: ConditionalPath,
    hashPreImages: Vector[NetworkElement] = Vector.empty)
    extends P2SHInputInfo {

  override def redeemScript: WitnessScriptPubKeyV0 =
    scriptWitness match {
      case p2wpkh: P2WPKHWitnessV0 => P2WPKHWitnessSPKV0(p2wpkh.pubKey)
      case p2wsh: P2WSHWitnessV0   => P2WSHWitnessSPKV0(p2wsh.redeemScript)
    }

  override val nestedInputInfo: SegwitV0NativeInputInfo =
    SegwitV0NativeInputInfo(outPoint,
                            amount,
                            scriptWitness,
                            conditionalPath,
                            hashPreImages)
}
