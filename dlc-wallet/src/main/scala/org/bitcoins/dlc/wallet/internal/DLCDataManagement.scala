package org.bitcoins.dlc.wallet.internal

import grizzled.slf4j.Logging
import org.bitcoins.core.api.dlc.wallet.db.DLCDb
import org.bitcoins.core.api.wallet.db.TransactionDb
import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.dlc.build.DLCTxBuilder
import org.bitcoins.core.protocol.dlc.execution._
import org.bitcoins.core.protocol.dlc.models.DLCMessage._
import org.bitcoins.core.protocol.dlc.models._
import org.bitcoins.core.protocol.dlc.sign.DLCTxSigner
import org.bitcoins.core.protocol.dlc.verify.DLCSignatureVerifier
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.core.util.sorted.{OrderedAnnouncements, OrderedNonces}
import org.bitcoins.core.wallet.utxo._
import org.bitcoins.crypto.Sha256Digest
import org.bitcoins.db.SafeDatabase
import org.bitcoins.dlc.wallet.DLCAppConfig
import org.bitcoins.dlc.wallet.models._
import org.bitcoins.dlc.wallet.util.DLCActionBuilder
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.wallet.models.TransactionDAO
import scodec.bits._
import slick.dbio.{DBIOAction, Effect, NoStream}

import scala.concurrent._
import scala.util.Try

/** Handles fetching and constructing different DLC datastructures from the database */
case class DLCDataManagement(dlcWalletDAOs: DLCWalletDAOs)(implicit
    ec: ExecutionContext)
    extends Logging {
  val dlcDAO = dlcWalletDAOs.dlcDAO
  private val dlcAnnouncementDAO = dlcWalletDAOs.dlcAnnouncementDAO
  private val dlcInputsDAO = dlcWalletDAOs.dlcInputsDAO
  //private val dlcOfferDAO = dlcWalletDAOs.dlcOfferDAO
  private val contractDataDAO = dlcWalletDAOs.contractDataDAO
  private val dlcAcceptDAO = dlcWalletDAOs.dlcAcceptDAO
  private val dlcSigsDAO: DLCCETSignaturesDAO = dlcWalletDAOs.dlcSigsDAO
  private val dlcRefundSigDAO: DLCRefundSigsDAO = dlcWalletDAOs.dlcRefundSigDAO
  private val announcementDAO = dlcWalletDAOs.oracleAnnouncementDAO
  private val oracleNonceDAO = dlcWalletDAOs.oracleNonceDAO
  private val remoteTxDAO = dlcWalletDAOs.dlcRemoteTxDAO

  private val actionBuilder: DLCActionBuilder = {
    DLCActionBuilder(dlcWalletDAOs)
  }
  private val safeDatabase: SafeDatabase = dlcDAO.safeDatabase

  private[wallet] def getDLCAnnouncementDbs(dlcId: Sha256Digest): Future[(
      Vector[DLCAnnouncementDb],
      Vector[OracleAnnouncementDataDb],
      Vector[OracleNonceDb])] = {
    val announcementsF = dlcAnnouncementDAO.findByDLCId(dlcId)
    val announcementIdsF: Future[Vector[Long]] = for {
      announcements <- announcementsF
      announcementIds = announcements.map(_.announcementId)
    } yield announcementIds
    val announcementDataF =
      announcementIdsF.flatMap(ids => announcementDAO.findByIds(ids))
    val noncesDbF =
      announcementIdsF.flatMap(ids => oracleNonceDAO.findByAnnouncementIds(ids))

    for {
      announcements <- announcementsF
      announcementData <- announcementDataF
      nonceDbs <- noncesDbF
    } yield {
      (announcements, announcementData, nonceDbs)
    }
  }

  /** Fetches the oracle announcements of the oracles
    * that were used for execution in a DLC
    */
  private[wallet] def getUsedOracleAnnouncements(
      dlcAnnouncementDbs: Vector[DLCAnnouncementDb],
      announcementData: Vector[OracleAnnouncementDataDb],
      nonceDbs: Vector[OracleNonceDb]): Vector[
    (OracleAnnouncementV0TLV, Long)] = {
    val withIds = nonceDbs
      .groupBy(_.announcementId)
      .toVector
      .flatMap { case (id, nonceDbs) =>
        announcementData.find(_.id.contains(id)) match {
          case Some(data) =>
            val used = dlcAnnouncementDbs
              .find(_.announcementId == data.id.get)
              .exists(_.used)
            if (used) {
              val nonces = nonceDbs.sortBy(_.index).map(_.nonce)
              val eventTLV = OracleEventV0TLV(OrderedNonces(nonces),
                                              data.eventMaturity,
                                              data.eventDescriptor,
                                              data.eventId)
              Some(
                (OracleAnnouncementV0TLV(data.announcementSignature,
                                         data.publicKey,
                                         eventTLV),
                 data.id.get))
            } else None
          case None =>
            throw new RuntimeException(s"Error no data for announcement id $id")
        }
      }
    dlcAnnouncementDbs
      .sortBy(_.index)
      .flatMap(a => withIds.find(_._2 == a.announcementId))
  }

  private[wallet] def getOracleAnnouncements(
      announcementIds: Vector[DLCAnnouncementDb],
      announcementData: Vector[OracleAnnouncementDataDb],
      nonceDbs: Vector[OracleNonceDb]): OrderedAnnouncements = {
    val announcements =
      getOracleAnnouncementsWithId(announcementIds, announcementData, nonceDbs)
        .map(_._1)
    OrderedAnnouncements(announcements)
  }

  private[wallet] def getOracleAnnouncementsWithId(
      announcementIds: Vector[DLCAnnouncementDb],
      announcementData: Vector[OracleAnnouncementDataDb],
      nonceDbs: Vector[OracleNonceDb]): Vector[
    (OracleAnnouncementV0TLV, Long)] = {
    val withIds = nonceDbs
      .groupBy(_.announcementId)
      .toVector
      .map { case (id, nonceDbs) =>
        announcementData.find(_.id.contains(id)) match {
          case Some(data) =>
            val nonces = nonceDbs.sortBy(_.index).map(_.nonce)
            val eventTLV = OracleEventV0TLV(OrderedNonces(nonces),
                                            data.eventMaturity,
                                            data.eventDescriptor,
                                            data.eventId)
            (OracleAnnouncementV0TLV(data.announcementSignature,
                                     data.publicKey,
                                     eventTLV),
             data.id.get)
          case None =>
            throw new RuntimeException(s"Error no data for announcement id $id")
        }
      }
    announcementIds
      .sortBy(_.index)
      .flatMap(a => withIds.find(_._2 == a.announcementId))
  }

  private[wallet] def getContractInfo(
      dlcId: Sha256Digest): Future[ContractInfo] = {
    for {
      contractData <- contractDataDAO.read(dlcId).map(_.get)
      (announcements, announcementData, nonceDbs) <- getDLCAnnouncementDbs(
        dlcId)
    } yield getContractInfo(contractData,
                            announcements,
                            announcementData,
                            nonceDbs)
  }

  private[wallet] def getContractInfo(
      contractDataDb: DLCContractDataDb,
      announcementIds: Vector[DLCAnnouncementDb],
      announcementData: Vector[OracleAnnouncementDataDb],
      nonceDbs: Vector[OracleNonceDb]): ContractInfo = {

    val announcementTLVs =
      getOracleAnnouncements(announcementIds, announcementData, nonceDbs)

    ContractDescriptor.fromTLV(contractDataDb.contractDescriptorTLV) match {
      case enum: EnumContractDescriptor =>
        val oracleInfo =
          if (announcementTLVs.size == 1) {
            EnumSingleOracleInfo(announcementTLVs.head)
          } else {
            EnumMultiOracleInfo(contractDataDb.oracleThreshold,
                                announcementTLVs)
          }
        SingleContractInfo(contractDataDb.totalCollateral.satoshis,
                           enum,
                           oracleInfo)
      case numeric: NumericContractDescriptor =>
        val oracleInfo =
          if (announcementTLVs.size == 1) {
            NumericSingleOracleInfo(announcementTLVs.head)
          } else {
            contractDataDb.oracleParamsTLVOpt match {
              case Some(params) =>
                NumericMultiOracleInfo(contractDataDb.oracleThreshold,
                                       announcementTLVs,
                                       params)
              case None =>
                NumericExactMultiOracleInfo(contractDataDb.oracleThreshold,
                                            announcementTLVs)
            }
          }
        SingleContractInfo(contractDataDb.totalCollateral.satoshis,
                           numeric,
                           oracleInfo)
    }
  }

  private def getDLCOfferData(
      dlcId: Sha256Digest,
      transactionDAO: TransactionDAO): Future[Option[OfferedDbState]] = {

    val combined = actionBuilder.getDLCOfferDataAction(dlcId)
    val combinedF = safeDatabase.run(combined)
    val announcementDataF = getDLCAnnouncementDbs(dlcId)
    val result: Future[Option[Future[OfferedDbState]]] = for {
      (dlcDbOpt, contractDataDbOpt, offerDbOpt, fundingInputDbs) <- combinedF
      (announcements, announcementData, nonceDbs) <- announcementDataF
      contractInfoOpt = {
        contractDataDbOpt.map { case contractData =>
          getContractInfo(contractData,
                          announcements,
                          announcementData,
                          nonceDbs)
        }
      }

      sortedInputs = fundingInputDbs.sortBy(_.index)

    } yield {
      for {
        dlcDb <- dlcDbOpt
        offerPrevTxsF = getOfferPrevTxs(dlcDb, fundingInputDbs, transactionDAO)
        contractDataDb <- contractDataDbOpt
        offerDb <- offerDbOpt
        contractInfo <- contractInfoOpt
      } yield {
        offerPrevTxsF.map { offerPrevTxs =>
          OfferedDbState(dlcDb = dlcDb,
                         contractDataDb = contractDataDb,
                         contractInfo = contractInfo,
                         offerDb = offerDb,
                         offerFundingInputsDb = sortedInputs,
                         offerPrevTxs = offerPrevTxs)
        }
      }
    }

    result.flatMap {
      case Some(f) => f.map(Some(_))
      case None    => Future.successful(None)
    }
  }

  private[wallet] def getDLCFundingData(
      contractId: ByteVector,
      txDAO: TransactionDAO
  ): Future[Option[DLCSetupDbState]] = {
    val dlcDbOpt = dlcDAO.findByContractId(contractId)
    dlcDbOpt.flatMap {
      case Some(d) =>
        getDLCFundingData(d.dlcId, txDAO)
      case None =>
        Future.successful(None)
    }
  }

  private[wallet] def getDLCFundingData(
      dlcId: Sha256Digest,
      txDAO: TransactionDAO): Future[Option[DLCSetupDbState]] = {
    val nestedF: Future[Option[Future[DLCSetupDbState]]] = for {
      offerDbStateOpt <- getDLCOfferData(dlcId, txDAO)
      dlcAcceptOpt <- dlcAcceptDAO.findByDLCId(dlcId)
      dlcFundingInputs <- dlcInputsDAO.findByDLCId(dlcId)
      (cetSignatures, refundSigsOpt) <- getCetAndRefundSigs(dlcId)
      acceptInputs = dlcFundingInputs.filterNot(_.isInitiator)
    } yield {
      for {
        offerDbState <- offerDbStateOpt
      } yield {
        //if the accept message is defined we must have refund sigs
        dlcAcceptOpt.zip(refundSigsOpt).headOption match {
          case Some((dlcAccept, refundSigDb)) =>
            require(
              refundSigsOpt.isDefined,
              s"Cannot have accept in the database if we do not have refund signatures, dlcId=${dlcId.hex}")
            val outcomeSigs = cetSignatures.map { dbSig =>
              dbSig.sigPoint -> dbSig.accepterSig
            }
            val signaturesOpt = {
              if (cetSignatures.isEmpty) {
                //means we have pruned signatures from the database
                //we have to return None
                None
              } else {
                val sigs = CETSignatures(outcomeSigs)
                Some(sigs)
              }
            }
            val acceptPrevTxsDbF =
              getAcceptPrevTxs(offerDbState.dlcDb, acceptInputs, txDAO)

            acceptPrevTxsDbF.map { case acceptPrevTxs =>
              offerDbState.toAcceptDb(
                acceptDb = dlcAccept,
                acceptFundingInputsDb = acceptInputs,
                acceptPrevTxsDb = acceptPrevTxs,
                cetSignaturesOpt = signaturesOpt,
                refundSigDb = refundSigDb
              )
            }
          case None =>
            //just return the offerDbState if we don't have an accept
            Future.successful(offerDbState)
        }
      }
    }

    val resultF = nestedF.flatMap {
      case Some(f) =>
        f.map(Some(_))
      case None => Future.successful(None)
    }
    resultF
  }

  private[wallet] def getAllDLCData(
      contractId: ByteVector,
      txDAO: TransactionDAO): Future[Option[DLCClosedDbState]] = {
    val resultF = for {
      dlcDbOpt <- dlcDAO.findByContractId(contractId)
      closedDbStateOptNested = dlcDbOpt.map(d => getAllDLCData(d.dlcId, txDAO))
    } yield {
      closedDbStateOptNested match {
        case Some(stateF) => stateF
        case None         => Future.successful(None)
      }
    }
    resultF.flatten
  }

  private[wallet] def getAllDLCData(
      dlcId: Sha256Digest,
      txDAO: TransactionDAO): Future[Option[DLCClosedDbState]] = {
    val sigDLCsF = dlcSigsDAO.findByDLCId(dlcId)

    for {
      setupStateOpt <- getDLCFundingData(dlcId, txDAO)
      sigs <- sigDLCsF
    } yield {
      //check if we have pruned signatures
      val sigsOpt = if (sigs.isEmpty) None else Some(sigs)
      val closedState = setupStateOpt.flatMap {
        case acceptState: AcceptDbState =>
          val closedState =
            DLCClosedDbState.fromSetupState(acceptState, sigsOpt)
          Some(closedState)
        case _: OfferedDbState =>
          //cannot return a closed state because we haven't seen the accept message
          None
      }
      closedState
    }
  }

  /** Build a verifier from an accept message.
    * Returns None if there is no DLC in the database associated with this accept message
    */
  private[wallet] def verifierFromAccept(
      accept: DLCAccept,
      txDAO: TransactionDAO): Future[Option[DLCSignatureVerifier]] = {
    for {
      dlcDbOpt <- dlcDAO.findByTempContractId(accept.tempContractId)

      offerDataOpt <- {
        dlcDbOpt.map(d => getDLCOfferData(d.dlcId, txDAO)) match {
          case Some(value) => value
          case None        => Future.successful(None)
        }
      }
    } yield {
      offerDataOpt match {
        case Some(offeredDataState) =>
          val offer = offeredDataState.offer
          val builder = DLCTxBuilder(offer, accept.withoutSigs)
          val verifier =
            DLCSignatureVerifier(builder, offeredDataState.dlcDb.isInitiator)
          Some(verifier)
        case None => None
      }
    }
  }

  private[wallet] def verifierFromDb(
      contractId: ByteVector,
      transactionDAO: TransactionDAO): Future[Option[DLCSignatureVerifier]] = {
    dlcDAO.findByContractId(contractId).flatMap { case dlcDbOpt =>
      val optF = dlcDbOpt.map(verifierFromDbData(_, transactionDAO))
      optF match {
        case Some(sigVerifierFOpt) => sigVerifierFOpt
        case None                  => Future.successful(None)
      }
    }
  }

  def getOfferAndAcceptWithoutSigs(
      dlcId: Sha256Digest,
      txDAO: TransactionDAO): Future[Option[AcceptDbState]] = {
    val dataF: Future[Option[DLCSetupDbState]] = getDLCFundingData(dlcId, txDAO)
    dataF.map {
      case Some(setupDbState) =>
        setupDbState match {
          case a: AcceptDbState  => Some(a)
          case _: OfferedDbState => None
        }
      case None => None
    }
  }

  private[wallet] def builderFromDbData(
      dlcDb: DLCDb,
      transactionDAO: TransactionDAO): Future[Option[DLCTxBuilder]] = {
    for {
      setupStateOpt <- getOfferAndAcceptWithoutSigs(dlcDb.dlcId, transactionDAO)
    } yield {
      setupStateOpt.map { acceptDbState =>
        val txBuilder = DLCTxBuilder(offer = acceptDbState.offer,
                                     accept = acceptDbState.acceptWithoutSigs)
        txBuilder
      }
    }
  }

  private[wallet] def verifierFromDbData(
      dlcDb: DLCDb,
      transactionDAO: TransactionDAO): Future[Option[DLCSignatureVerifier]] = {
    val builderOptF =
      builderFromDbData(dlcDb = dlcDb, transactionDAO = transactionDAO)

    builderOptF.map {
      case Some(builder) =>
        val verifier = DLCSignatureVerifier(builder, dlcDb.isInitiator)
        Some(verifier)
      case None => None
    }
  }

  private[wallet] def signerFromDb(
      dlcId: Sha256Digest,
      transactionDAO: TransactionDAO,
      fundingUtxoScriptSigParams: Vector[ScriptSignatureParams[InputInfo]],
      keyManager: BIP39KeyManager): Future[Option[DLCTxSigner]] = {
    for {
      dlcDbOpt <- dlcDAO.findByDLCId(dlcId)
      signerOpt <- {
        dlcDbOpt match {
          case Some(dlcDb) =>
            val signerOptF = signerFromDb(
              dlcDb = dlcDb,
              fundingUtxoScriptSigParams = fundingUtxoScriptSigParams,
              transactionDAO = transactionDAO,
              keyManager = keyManager
            )
            signerOptF
          case None =>
            Future.successful(None)
        }
      }
    } yield signerOpt
  }

  private[wallet] def signerFromDb(
      dlcDb: DLCDb,
      fundingUtxoScriptSigParams: Vector[ScriptSignatureParams[InputInfo]],
      transactionDAO: TransactionDAO,
      keyManager: BIP39KeyManager): Future[Option[DLCTxSigner]] = {
    for {
      builderOpt <- builderFromDbData(
        dlcDb = dlcDb,
        transactionDAO = transactionDAO
      )
    } yield {
      builderOpt match {
        case Some(builder) =>
          val dlcOffer = builder.offer
          val dlcAccept = builder.accept
          val (fundingKey, payoutAddress) = if (dlcDb.isInitiator) {
            (dlcOffer.pubKeys.fundingKey, dlcOffer.pubKeys.payoutAddress)
          } else {
            (dlcAccept.pubKeys.fundingKey, dlcAccept.pubKeys.payoutAddress)
          }

          val bip32Path = BIP32Path(
            dlcDb.account.path ++ Vector(
              BIP32Node(dlcDb.changeIndex.index, hardened = false),
              BIP32Node(dlcDb.keyIndex, hardened = false)))

          val privKeyPath = HDPath.fromString(bip32Path.toString)
          val fundingPrivKey = keyManager.toSign(privKeyPath)

          require(fundingKey == fundingPrivKey.publicKey)

          val signer = DLCTxSigner(builder = builder,
                                   isInitiator = dlcDb.isInitiator,
                                   fundingKey = fundingPrivKey,
                                   finalAddress = payoutAddress,
                                   fundingUtxos = fundingUtxoScriptSigParams)
          Some(signer)
        case None => None
      }
    }
  }

  private[wallet] def executorFromDb(
      dlcDb: DLCDb,
      fundingUtxoScriptSigParams: Vector[ScriptSignatureParams[InputInfo]],
      transactionDAO: TransactionDAO,
      keyManager: BIP39KeyManager): Future[Option[DLCExecutor]] = {
    val signerOptF = signerFromDb(
      dlcDb = dlcDb,
      fundingUtxoScriptSigParams = fundingUtxoScriptSigParams,
      transactionDAO = transactionDAO,
      keyManager = keyManager
    )
    signerOptF.map {
      case Some(dlcTxSigner) =>
        val e = DLCExecutor(dlcTxSigner)
        Some(e)
      case None => None
    }
  }

  private[wallet] def executorFromDb(
      dlcId: Sha256Digest,
      transactionDAO: TransactionDAO,
      fundingUtxoScriptSigParams: Vector[ScriptSignatureParams[InputInfo]],
      keyManager: BIP39KeyManager): Future[Option[DLCExecutor]] = {
    val signerOptF = signerFromDb(dlcId = dlcId,
                                  transactionDAO = transactionDAO,
                                  fundingUtxoScriptSigParams =
                                    fundingUtxoScriptSigParams,
                                  keyManager = keyManager)
    signerOptF.map {
      case Some(dlcTxSigner) =>
        val e = DLCExecutor(dlcTxSigner)
        Some(e)
      case None => None
    }
  }

  /** Builds an [[DLCExecutor]] and [[SetupDLC]] for a given contract id
    * @return the executor and setup if we still have CET signatures else return None
    */
  private[wallet] def executorAndSetupFromDb(
      contractId: ByteVector,
      txDAO: TransactionDAO,
      fundingUtxoScriptSigParams: Vector[ScriptSignatureParams[InputInfo]],
      keyManager: BIP39KeyManager): Future[Option[DLCExecutorWithSetup]] = {
    getAllDLCData(contractId, txDAO).flatMap {
      case Some(closedDbState) =>
        closedDbState match {
          case withCETSigs: ClosedDbStateWithCETSigs =>
            executorAndSetupFromDb(
              dlcDb = withCETSigs.dlcDb,
              refundSigsDb = withCETSigs.refundSigsDb,
              fundingInputs = withCETSigs.allFundingInputs,
              outcomeSigsDbs = withCETSigs.cetSigs,
              transactionDAO = txDAO,
              fundingUtxoScriptSigParams = fundingUtxoScriptSigParams,
              keyManager = keyManager
            )
          case _: ClosedDbStateNoCETSigs =>
            //means we cannot re-create messages because
            //we don't have the cets in the database anymore
            Future.successful(None)
        }
      case None => Future.successful(None)
    }
  }

  private[wallet] def executorAndSetupFromDb(
      dlcDb: DLCDb,
      refundSigsDb: DLCRefundSigsDb,
      fundingInputs: Vector[DLCFundingInputDb],
      outcomeSigsDbs: Vector[DLCCETSignaturesDb],
      transactionDAO: TransactionDAO,
      fundingUtxoScriptSigParams: Vector[ScriptSignatureParams[InputInfo]],
      keyManager: BIP39KeyManager): Future[Option[DLCExecutorWithSetup]] = {

    val dlcExecutorOptF = executorFromDb(
      dlcDb = dlcDb,
      fundingUtxoScriptSigParams = fundingUtxoScriptSigParams,
      transactionDAO = transactionDAO,
      keyManager = keyManager
    )

    dlcExecutorOptF.flatMap {
      case Some(executor) =>
        // Filter for only counterparty's outcome sigs
        val outcomeSigs = if (dlcDb.isInitiator) {
          outcomeSigsDbs
            .map { dbSig =>
              dbSig.sigPoint -> dbSig.accepterSig
            }
        } else {
          outcomeSigsDbs
            .map { dbSig =>
              dbSig.sigPoint -> dbSig.initiatorSig.get
            }
        }
        val refundSig = if (dlcDb.isInitiator) {
          refundSigsDb.accepterSig
        } else refundSigsDb.initiatorSig.get

        //sometimes we do not have cet signatures, for instance
        //if we have settled a DLC, we prune the cet signatures
        //from the database
        val cetSigs = CETSignatures(outcomeSigs)

        val setupF: Try[SetupDLC] = if (dlcDb.isInitiator) {
          // Note that the funding tx in this setup is not signed
          executor.setupDLCOffer(cetSigs, refundSig)
        } else {
          val fundingSigs =
            fundingInputs
              .filter(_.isInitiator)
              .map { input =>
                input.witnessScriptOpt match {
                  case Some(witnessScript) =>
                    witnessScript match {
                      case EmptyScriptWitness =>
                        throw new RuntimeException(
                          "Script witness cannot be empty")
                      case witness: ScriptWitnessV0 => (input.outPoint, witness)
                    }
                  case None => throw new RuntimeException("")
                }
              }
          executor.setupDLCAccept(cetSigs,
                                  refundSig,
                                  FundingSignatures(fundingSigs),
                                  None)
        }

        val x: Try[DLCExecutorWithSetup] =
          setupF.map(DLCExecutorWithSetup(executor, _))
        Future
          .fromTry(x)
          .map(Some(_))
      case None =>
        Future.successful(None)
    }
  }

  def getCetAndRefundSigsAction(dlcId: Sha256Digest): DBIOAction[
    (Vector[DLCCETSignaturesDb], Option[DLCRefundSigsDb]),
    NoStream,
    Effect.Read] = {
    val cetSigsAction = dlcSigsDAO.findByDLCIdAction(dlcId)
    val refundSigsAction = dlcRefundSigDAO.findByDLCIdAction(dlcId)
    for {
      cetSigs <- cetSigsAction
      refundSigs <- refundSigsAction
    } yield (cetSigs, refundSigs)
  }

  def getCetAndRefundSigs(dlcId: Sha256Digest): Future[
    (Vector[DLCCETSignaturesDb], Option[DLCRefundSigsDb])] = {
    val action = getCetAndRefundSigsAction(dlcId)
    safeDatabase.run(action)
  }

  /** Retrieves the transaction(s) used to fund the offer message */
  private def getOfferPrevTxs(
      dlcDb: DLCDb,
      fundingInputs: Vector[DLCFundingInputDb],
      txDAO: TransactionDAO): Future[Vector[TransactionDb]] = {
    val txIds = fundingInputs.map(_.outPoint.txIdBE)
    if (dlcDb.isInitiator) {
      //query txDAO as we created the offer
      txDAO.findByTxIdBEs(txIds)
    } else {
      //query remote tx dao as we didn't create the offers
      remoteTxDAO.findByTxIdBEs(txIds)
    }
  }

  /** Retreives the transaction(s) used to fund the accept message */
  private def getAcceptPrevTxs(
      dlcDb: DLCDb,
      fundingInputs: Vector[DLCFundingInputDb],
      txDAO: TransactionDAO): Future[Vector[TransactionDb]] = {
    val txIds = fundingInputs.map(_.outPoint.txIdBE)
    if (dlcDb.isInitiator) {
      //if we are the initiator we need to query the remote tx dao
      remoteTxDAO.findByTxIdBEs(txIds)
    } else {
      //else they are in our local tx dao
      txDAO.findByTxIdBEs(txIds)
    }
  }
}

object DLCDataManagement {

  def fromDbAppConfig()(implicit
      dbAppConfig: DLCAppConfig,
      ec: ExecutionContext): DLCDataManagement = {
    val announcementDAO: OracleAnnouncementDataDAO =
      OracleAnnouncementDataDAO()
    val oracleNonceDAO: OracleNonceDAO = OracleNonceDAO()

    val dlcAnnouncementDAO: DLCAnnouncementDAO =
      DLCAnnouncementDAO()
    val dlcOfferDAO: DLCOfferDAO = DLCOfferDAO()
    val dlcAcceptDAO: DLCAcceptDAO = DLCAcceptDAO()
    val dlcDAO: DLCDAO = DLCDAO()

    val contractDataDAO: DLCContractDataDAO =
      DLCContractDataDAO()
    val dlcInputsDAO: DLCFundingInputDAO = DLCFundingInputDAO()
    val dlcSigsDAO: DLCCETSignaturesDAO = DLCCETSignaturesDAO()
    val dlcRefundSigDAO: DLCRefundSigsDAO = DLCRefundSigsDAO()
    val dlcRemoteTxDAO: DLCRemoteTxDAO = DLCRemoteTxDAO()

    val dlcWalletDAOs = DLCWalletDAOs(
      dlcDAO,
      contractDataDAO,
      dlcAnnouncementDAO,
      dlcInputsDAO,
      dlcOfferDAO,
      dlcAcceptDAO,
      dlcSigsDAO,
      dlcRefundSigDAO,
      oracleNonceDAO,
      announcementDAO,
      dlcRemoteTxDAO
    )

    DLCDataManagement(dlcWalletDAOs)
  }
}
