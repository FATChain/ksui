package xyz.mcxross.ksui.client

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.errors.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import org.gciatto.kt.math.BigInteger
import xyz.mcxross.ksui.model.Balance
import xyz.mcxross.ksui.model.Checkpoint
import xyz.mcxross.ksui.model.CheckpointDigest
import xyz.mcxross.ksui.model.CheckpointId
import xyz.mcxross.ksui.model.CheckpointSequenceNumber
import xyz.mcxross.ksui.model.DevInspectResults
import xyz.mcxross.ksui.model.Digest
import xyz.mcxross.ksui.model.DryRunTransactionResponse
import xyz.mcxross.ksui.model.EndPoint
import xyz.mcxross.ksui.model.Event
import xyz.mcxross.ksui.model.ExecuteTransactionRequestType
import xyz.mcxross.ksui.model.Gas
import xyz.mcxross.ksui.model.GasPrice
import xyz.mcxross.ksui.model.MutateObject
import xyz.mcxross.ksui.model.ObjectID
import xyz.mcxross.ksui.model.RPCTransactionRequestParams
import xyz.mcxross.ksui.model.Response
import xyz.mcxross.ksui.model.SuiAddress
import xyz.mcxross.ksui.model.SuiCoinMetadata
import xyz.mcxross.ksui.model.SuiCommittee
import xyz.mcxross.ksui.model.SuiException
import xyz.mcxross.ksui.model.SuiJsonValue
import xyz.mcxross.ksui.model.SuiObjectInfo
import xyz.mcxross.ksui.model.SuiObjectResult
import xyz.mcxross.ksui.model.SuiSystemStateSummary
import xyz.mcxross.ksui.model.SuiTransactionBuilderMode
import xyz.mcxross.ksui.model.Supply
import xyz.mcxross.ksui.model.Transaction
import xyz.mcxross.ksui.model.TransactionBlockResponse
import xyz.mcxross.ksui.model.TransactionBlockResponseOptions
import xyz.mcxross.ksui.model.TransactionBytes
import xyz.mcxross.ksui.model.TransactionDigest
import xyz.mcxross.ksui.model.TransactionDigests
import xyz.mcxross.ksui.model.TransactionNumber
import xyz.mcxross.ksui.model.TransactionQuery
import xyz.mcxross.ksui.model.TransactionsPage
import xyz.mcxross.ksui.model.TypeTag
import xyz.mcxross.ksui.model.Validators

/** A Kotlin wrapper around the Sui JSON-RPC API for interacting with a Sui full node. */
class SuiHttpClient constructor(private val configContainer: ConfigContainer) : SuiClient {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  private fun whichUrl(endPoint: EndPoint): String {
    return when (endPoint) {
      EndPoint.CUSTOM -> {
        configContainer.customUrl
      }
      EndPoint.DEVNET -> {
        "https://fullnode.devnet.sui.io:443"
      }
      EndPoint.TESTNET -> {
        "https://fullnode.testnet.sui.io:443"
      }
      EndPoint.MAINNET -> {
        "https://fullnode.sui.io:443"
      }
    }
  }

  /**
   * Calls a Sui RPC method with the given name and parameters.
   *
   * @param method The name of the Sui RPC method to call.
   * @param params The parameters to pass to the Sui RPC method.
   * @return The result of the Sui RPC method, or null if there was an error.
   */
  @Throws(
    SuiException::class,
    CancellationException::class,
    IOException::class,
    IllegalStateException::class,
  )
  private suspend fun call(method: String, vararg params: Any): HttpResponse {
    val response: HttpResponse =
      configContainer.httpClient.post {
        url(whichUrl(configContainer.endPoint))
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", 1)
              put("method", method)
              // add an array of params to the request body
              putJsonArray("params") {
                params.forEach {
                  when (it) {
                    is String -> add(it)
                    is Int -> add(it)
                    is Long -> add(it)
                    is Boolean -> add(it)
                    is JsonElement -> add(it)
                    else -> add(json.encodeToString(serializer(), it))
                  }
                }
              }
            }
            .toString()
        )
      }

    if (response.status.isSuccess()) {
      return response
    } else {
      throw SuiException(response.status.value.toString())
    }
  }

  /**
   * Pings Sui using the configured endpoint URL and HTTP client.
   *
   * @return `true` if the Sui is available and returns a successful response, `false` otherwise.
   */
  suspend fun isSuiAvailable(): Boolean = call("isSuiAvailable").status.isSuccess()

  /**
   * Create an unsigned batched transaction.
   *
   * @param signer transaction signer's Sui address
   * @param singleTransactionParams list of transaction request parameters
   * @param gas object to be used in this transaction, node will pick one from the signer's
   *   possession if not provided
   * @param gasBudget the gas budget, the transaction will fail if the gas cost exceed the budget
   * @param txnBuilderMode whether this is a regular transaction or a Dev Inspect Transaction
   * @return [TransactionBytes]
   */
  suspend fun batchTransaction(
    signer: SuiAddress,
    singleTransactionParams: List<RPCTransactionRequestParams>,
    gas: Gas? = null,
    gasBudget: Int,
    txnBuilderMode: SuiTransactionBuilderMode = SuiTransactionBuilderMode.REGULAR
  ): TransactionBytes =
    Json.decodeFromString(
      serializer(),
      when (gas) {
        null ->
          call(
            "sui_batchTransaction",
            signer.pubKey,
            singleTransactionParams,
            gasBudget,
            when (txnBuilderMode) {
              SuiTransactionBuilderMode.REGULAR -> "Commit"
              SuiTransactionBuilderMode.DEV_INSPECT -> ""
            },
          )
        else ->
          call(
            "sui_batchTransaction",
            signer.pubKey,
            singleTransactionParams,
            gas,
            gasBudget,
            when (txnBuilderMode) {
              SuiTransactionBuilderMode.REGULAR -> "Commit"
              SuiTransactionBuilderMode.DEV_INSPECT -> ""
            },
          )
      }.bodyAsText(),
    )

  suspend fun devInspectTransaction(
    senderAddress: SuiAddress,
    txBytes: String,
    gasPrice: BigInteger = BigInteger.of(0),
    epoch: BigInteger? = null
  ): DevInspectResults =
    Json.decodeFromString(
      serializer(),
      when (epoch) {
        null -> call("sui_devInspectTransaction", senderAddress.pubKey, txBytes, gasPrice)
        else -> call("sui_devInspectTransaction", senderAddress.pubKey, txBytes, gasPrice, epoch)
      }.bodyAsText(),
    )

  suspend fun dryRunTransaction(txBytes: BigInteger): DryRunTransactionResponse =
    Json.decodeFromString(serializer(), call("sui_dryRunTransaction", txBytes).bodyAsText())

  suspend fun executeTransaction(
    txBytes: BigInteger,
    signature: BigInteger,
    requestType: ExecuteTransactionRequestType
  ): TransactionBlockResponse =
    Json.decodeFromString(
      serializer(),
      call("sui_executeTransaction", txBytes, signature, requestType).bodyAsText(),
    )

  suspend fun executeTransactionSerializedSig() {}

  /**
   * Return the total coin balance for all coin type, owned by the address owner.
   *
   * @param owner's Sui address.
   * @return [List<[Balance]>]
   */
  suspend fun getAllBalances(owner: SuiAddress): List<Balance> {
    val result =
      json.decodeFromString<Response<List<Balance>>>(
        serializer(),
        call("suix_getAllBalances", owner.pubKey).bodyAsText()
      )
    when (result) {
      is Response.Ok -> return result.data
      is Response.Error -> throw SuiException(result.message)
    }
  }

  /**
   * Return the total coin balance for one coin type, owned by the address owner.
   *
   * @param owner's Sui address.
   * @param coinType names for the coin. Defaults to "0x2::sui::SUI"
   * @return [Balance]
   */
  suspend fun getBalance(owner: SuiAddress, coinType: String = "0x2::sui::SUI"): Balance {
    val response =
      json.decodeFromString<Response<Balance>>(
        serializer(),
        call("suix_getBalance", *listOf(owner.pubKey, coinType).toTypedArray()).bodyAsText()
      )
    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }

  suspend fun getAllCoins() {}

  suspend fun getCheckpoint(checkpointId: CheckpointId): Checkpoint {
    val response =
      json.decodeFromString<Response<Checkpoint>>(
        serializer(),
        call("sui_getCheckpoint", checkpointId.digest).bodyAsText()
      )

    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }
  suspend fun getCheckpointContents() {}
  suspend fun getCheckpointContentsByDigest() {}
  suspend fun getCheckpointSummary() {}
  suspend fun getCheckpointSummaryByDigest(digest: CheckpointDigest) {
    return Json.decodeFromString(
      serializer(),
      call("sui_getCheckpointSummaryByDigest", digest).bodyAsText()
    )
  }

  /**
   * Return metadata(e.g., symbol, decimals) for a coin
   *
   * @param coinType name for the coin.
   * @return [SuiCoinMetadata]
   */
  suspend fun getCoinMetadata(coinType: String): SuiCoinMetadata {
    val response =
      json.decodeFromString<Response<SuiCoinMetadata>>(
        serializer(),
        call("suix_getCoinMetadata", *listOf(coinType).toTypedArray()).bodyAsText()
      )
    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }
  suspend fun getCoins() {}
  suspend fun getCommitteeInfo(epoch: Long? = null): SuiCommittee {
    val response =
      json.decodeFromString<Response<SuiCommittee>>(
        serializer(),
        when (epoch) {
          null -> call("suix_getCommitteeInfo").bodyAsText()
          else -> call("suix_getCommitteeInfo", epoch).bodyAsText()
        }
      )
    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }

  suspend fun getDelegatedStakes() {}
  suspend fun getDynamicFieldObject() {}
  suspend fun getDynamicFields() {}

  /**
   * Return the latest SUI system state object on-chain.
   *
   * @return [SuiSystemStateSummary]
   */
  suspend fun getLatestSuiSystemState(): SuiSystemStateSummary {
    val response =
      json.decodeFromString<Response<SuiSystemStateSummary>>(
        serializer(),
        call("suix_getLatestSuiSystemState").bodyAsText()
      )
    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }

  suspend fun getEvents() {}

  /**
   * Return the sequence number of the latest checkpoint that has been executed
   *
   * @return [CheckpointSequenceNumber]
   */
  suspend fun getLatestCheckpointSequenceNumber(): CheckpointSequenceNumber =
    json.decodeFromString(serializer(), call("sui_getLatestCheckpointSequenceNumber").bodyAsText())

  suspend fun getMoveFunctionArgTypes() {}
  suspend fun getNormalizedMoveFunction() {}
  suspend fun getNormalizedMoveModule() {}
  suspend fun getNormalizedMoveModulesByPackage() {}
  suspend fun getNormalizedMoveStruct() {}
  suspend fun getObject() {}

  /**
   * Return the list of objects owned by an address.
   *
   * @param address of the owner
   * @return List<[SuiObjectInfo]>
   */
  suspend fun getObjectsOwnedByAddress(address: SuiAddress): List<SuiObjectInfo> {
    val result =
      json.decodeFromString<SuiObjectResult>(
        serializer(),
        call("sui_getObjectsOwnedByAddress", *listOf(address.pubKey).toTypedArray()).bodyAsText()
      )
    return result.value.map {
      SuiObjectInfo(
        it.objectId,
        it.version,
        Digest(it.digest),
        it.type,
        it.owner,
        Transaction(it.previousTransaction)
      )
    }
  }

  /**
   * Return the reference gas price for the network.
   *
   * @return [GasPrice]
   */
  suspend fun getReferenceGasPrice(): GasPrice =
    json.decodeFromString(serializer(), call("sui_getReferenceGasPrice").bodyAsText())

  suspend fun getSuiSystemState() {}

  /**
   * Retrieves the total supply for a specified cryptocurrency.
   *
   * @param coinType The type of cryptocurrency to retrieve the supply for.
   * @return The total supply of the specified cryptocurrency.
   * @throws SuiException if there is an error retrieving the supply.
   */
  suspend fun getTotalSupply(coinType: String): Supply {
    val response =
      json.decodeFromString<Response<Supply>>(
        serializer(),
        call("suix_getTotalSupply", coinType).bodyAsText()
      )
    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }

  /**
   * Return the total number of transactions known to the server.
   *
   * @return [Long]
   */
  suspend fun getTotalTransactionNumber(): Long =
    json
      .decodeFromString<TransactionNumber>(
        serializer(),
        call("sui_getTotalTransactionNumber").bodyAsText()
      )
      .value

  @OptIn(ExperimentalSerializationApi::class)
  suspend fun getTransactionBlock(
    digest: TransactionDigest,
    options: TransactionBlockResponseOptions
  ): TransactionBlockResponse {
    val module = SerializersModule {
      polymorphic(MutateObject::class) {
        subclass(MutateObject.MutatedObjectShared::class)
        defaultDeserializer { MutateObject.MutatedObject.serializer() }
      }

      polymorphic(Event::class) { default { Event.EventEvent.serializer() } }
    }
    val json = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      serializersModule = module
    }
    val response =
      json.decodeFromString<Response<TransactionBlockResponse>>(
        serializer(),
        call(
            "sui_getTransactionBlock",
            *listOf(digest.value, json.encodeToJsonElement(serializer(), options)).toTypedArray()
          )
          .bodyAsText()
      )
    when (response) {
      is Response.Ok -> return response.data
      is Response.Error -> throw SuiException(response.message)
    }
  }

  suspend fun getTransactions(
    query: TransactionQuery,
    cursor: TransactionDigest,
    limit: Long,
    descendingOrder: Boolean = false
  ): TransactionsPage =
    Json.decodeFromString(
      serializer(),
      call("sui_getTransactions", query, cursor, limit, descendingOrder).bodyAsText()
    )
  suspend fun getTransactionsInRange(start: Long, end: Long): TransactionDigests =
    json.decodeFromString(serializer(), call("sui_getTransactionsInRange", start, end).bodyAsText())
  suspend fun getValidators(): Validators =
    json.decodeFromString(serializer(), call("sui_getValidators").bodyAsText())

  suspend fun mergeCoins(
    signer: SuiAddress,
    primaryCoin: ObjectID,
    coinToMerge: ObjectID,
    gas: Gas,
    gasBudget: Long
  ): TransactionBytes =
    Json.decodeFromString(
      serializer(),
      call("sui_mergeCoins", signer, primaryCoin, coinToMerge, gas, gasBudget).bodyAsText()
    )
  suspend fun moveCall(
    signer: SuiAddress,
    packageObjectId: ObjectID,
    module: String,
    function: String,
    typeArguments: TypeTag,
    arguments: List<SuiJsonValue>,
    gas: Gas,
    gasBudget: Long,
    executionMode: SuiTransactionBuilderMode = SuiTransactionBuilderMode.REGULAR
  ): TransactionBytes {
    return Json.decodeFromString(
      serializer(),
      call(
          "sui_moveCall",
          signer,
          packageObjectId,
          module,
          function,
          typeArguments,
          arguments,
          gas,
          gasBudget,
          executionMode
        )
        .bodyAsText()
    )
  }
  suspend fun multiGetTransactions() {}
  suspend fun pay() {}
  suspend fun payAllSui() {}
  suspend fun paySui() {}
  suspend fun publish() {}
  suspend fun requestAddDelegation() {}
  suspend fun requestWithdrawDelegation() {}
  suspend fun splitCoin() {}
  suspend fun splitCoinEqual() {}
  suspend fun submitTransaction() {}
  suspend fun subscribeEvent() {}
  suspend fun tblsSignRandomnessObject() {}
  suspend fun transferObject() {}
  suspend fun transferSui() {}
  suspend fun tryGetPastObject() {}
}
