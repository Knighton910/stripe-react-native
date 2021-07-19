package com.reactnativestripesdk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.AsyncTask
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.google.android.gms.wallet.*
import com.stripe.android.*
import com.stripe.android.model.*
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.view.AddPaymentMethodActivityStarter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class StripeSdkModule(reactContext: ReactApplicationContext, cardFieldManager: StripeSdkCardViewManager) : ReactContextBaseJavaModule(reactContext) {
  private var cardFieldManager: StripeSdkCardViewManager = cardFieldManager
  private var paymentsClient: PaymentsClient? = null

  companion object {
    private const val LOAD_PAYMENT_DATA_REQUEST_CODE = 53
  }

  override fun getName(): String {
    return "StripeSdk"
  }
  private lateinit var stripe: Stripe

  private lateinit var publishableKey: String
  private var stripeAccountId: String? = null
  private var paymentSheetFragment: PaymentSheetFragment? = null

  private var urlScheme: String? = null
  private var confirmPromise: Promise? = null
  private var handleCardActionPromise: Promise? = null
  private var confirmSetupIntentPromise: Promise? = null
  private var confirmPaymentSheetPaymentPromise: Promise? = null
  private var presentPaymentSheetPromise: Promise? = null
  private var initPaymentSheetPromise: Promise? = null
  private var confirmPaymentClientSecret: String? = null
  private var payWithGooglePromise: Promise? = null

  private var paymentIntentClientSecret: String? = null

  private val mActivityEventListener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      if (::stripe.isInitialized) {
        when (requestCode) {
          LOAD_PAYMENT_DATA_REQUEST_CODE -> {
            when (resultCode) {
              Activity.RESULT_OK -> {
                if (data != null) {
                  onGooglePayResult(data)
                }
              }
              Activity.RESULT_CANCELED -> {
                payWithGooglePromise?.resolve(createError(GooglePayErrorType.Canceled.toString(), "Google pay has been canceled"))
              }
              AutoResolveHelper.RESULT_ERROR -> {
                val status = AutoResolveHelper.getStatusFromIntent(data)
                payWithGooglePromise?.resolve(createError(GooglePayErrorType.Failed.toString(), status.statusMessage))
              }
            }
          }
        }

        stripe.onSetupResult(requestCode, data, object : ApiResultCallback<SetupIntentResult> {
          override fun onSuccess(result: SetupIntentResult) {
            val setupIntent = result.intent
            when (setupIntent.status) {
              StripeIntent.Status.Succeeded -> {
                confirmSetupIntentPromise?.resolve(createResult("setupIntent", mapFromSetupIntentResult(setupIntent)))
              }
              StripeIntent.Status.Canceled -> {
                confirmSetupIntentPromise?.resolve(createError(ConfirmSetupIntentErrorType.Canceled.toString(), setupIntent.lastSetupError))
              }
              StripeIntent.Status.RequiresAction -> {
                confirmSetupIntentPromise?.resolve(createError(ConfirmSetupIntentErrorType.Canceled.toString(), setupIntent.lastSetupError))
              }
              else -> {
                val errorMessage = "unhandled error: ${setupIntent.status}"
                confirmSetupIntentPromise?.resolve(createError(ConfirmSetupIntentErrorType.Failed.toString(), errorMessage))
              }
            }
          }

          override fun onError(e: Exception) {
            confirmSetupIntentPromise?.resolve(createError(ConfirmSetupIntentErrorType.Failed.toString(), e))
          }
        })

        stripe.onPaymentResult(requestCode, data, object : ApiResultCallback<PaymentIntentResult> {
          override fun onSuccess(result: PaymentIntentResult) {
            val paymentIntent = result.intent

            when (paymentIntent.status) {
              StripeIntent.Status.Succeeded,
              StripeIntent.Status.Processing,
              StripeIntent.Status.RequiresCapture -> {
                val pi = createResult("paymentIntent", mapFromPaymentIntentResult(paymentIntent))
                confirmPromise?.resolve(pi)
                handleCardActionPromise?.resolve(pi)
              }
              StripeIntent.Status.RequiresAction -> {
                if (isPaymentIntentNextActionVoucherBased(paymentIntent.nextActionType)) {
                  val pi = createResult("paymentIntent", mapFromPaymentIntentResult(paymentIntent))
                  confirmPromise?.resolve(pi)
                  handleCardActionPromise?.resolve(pi)
                } else {
                  (paymentIntent.lastPaymentError)?.let {
                    confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), it))
                    handleCardActionPromise?.resolve(createError(NextPaymentActionErrorType.Canceled.toString(), it))
                  } ?: run {
                    confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), "The payment has been canceled"))
                    handleCardActionPromise?.resolve(createError(NextPaymentActionErrorType.Canceled.toString(), "The payment has been canceled"))
                  }
                }
              }
              StripeIntent.Status.RequiresPaymentMethod -> {
                val error = paymentIntent.lastPaymentError
                confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), error))
                handleCardActionPromise?.resolve(createError(NextPaymentActionErrorType.Failed.toString(), error))
              }
              StripeIntent.Status.RequiresConfirmation -> {
                val pi = createResult("paymentIntent", mapFromPaymentIntentResult(paymentIntent))
                handleCardActionPromise?.resolve(pi)
              }
              StripeIntent.Status.Canceled -> {
                val error = paymentIntent.lastPaymentError
                confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), error))
                handleCardActionPromise?.resolve(createError(NextPaymentActionErrorType.Canceled.toString(), error))
              }
              else -> {
                val errorMessage = "unhandled error: ${paymentIntent.status}"
                confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Unknown.toString(), errorMessage))
                handleCardActionPromise?.resolve(createError(NextPaymentActionErrorType.Unknown.toString(), errorMessage))
              }
            }
          }

          override fun onError(e: Exception) {
            confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), e))
            handleCardActionPromise?.resolve(createError(NextPaymentActionErrorType.Failed.toString(), e))
          }
        })

        paymentSheetFragment?.activity?.activityResultRegistry?.dispatchResult(requestCode, resultCode, data)

        try {
          val result = AddPaymentMethodActivityStarter.Result.fromIntent(data)
          if (data?.getParcelableExtra<Parcelable>("extra_activity_result") != null) {
            onFpxPaymentMethodResult(result)
          }
        } catch (e: java.lang.Exception) {
          Log.d("Error", e.localizedMessage ?: e.toString())
        }
      }
    }
  }

  init {
    reactContext.addActivityEventListener(mActivityEventListener);
  }

  private fun configure3dSecure(params: ReadableMap) {
    val stripe3dsConfigBuilder = PaymentAuthConfig.Stripe3ds2Config.Builder()
    if (params.hasKey("timeout")) stripe3dsConfigBuilder.setTimeout(params.getInt("timeout"))
    val uiCustomization = mapToUICustomization(params)

    PaymentAuthConfig.init(
      PaymentAuthConfig.Builder()
        .set3ds2Config(
          stripe3dsConfigBuilder
            .setUiCustomization(uiCustomization)
            .build()
        )
        .build()
    )
  }

  private val mPaymentSheetReceiver: BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent) {
      if (intent.action == ON_PAYMENT_SHEET_FRAGMENT_CREATED) {
        paymentSheetFragment = (currentActivity as AppCompatActivity).supportFragmentManager.findFragmentByTag("payment_sheet_launch_fragment") as PaymentSheetFragment
      }
      if (intent.action == ON_PAYMENT_RESULT_ACTION) {
        when (val result = intent.extras?.getParcelable<PaymentSheetResult>("paymentResult")) {
          is PaymentSheetResult.Canceled -> {
            val message = "The payment has been canceled"
            confirmPaymentSheetPaymentPromise?.resolve(createError(PaymentSheetErrorType.Canceled.toString(), message))
            presentPaymentSheetPromise?.resolve(createError(PaymentSheetErrorType.Canceled.toString(), message))
          }
          is PaymentSheetResult.Failed -> {
            confirmPaymentSheetPaymentPromise?.resolve(createError(PaymentSheetErrorType.Failed.toString(), result.error))
            presentPaymentSheetPromise?.resolve(createError(PaymentSheetErrorType.Failed.toString(), result.error))
          }
          is PaymentSheetResult.Completed -> {
            confirmPaymentSheetPaymentPromise?.resolve(WritableNativeMap())
            presentPaymentSheetPromise?.resolve(WritableNativeMap())
          }
        }
      } else if (intent.action == ON_PAYMENT_OPTION_ACTION) {
        val label = intent.extras?.getString("label")
        val image = intent.extras?.getString("image")

        if (label != null && image != null) {
          val option: WritableMap = WritableNativeMap()
          option.putString("label", label)
          option.putString("image", image)
          presentPaymentSheetPromise?.resolve(createResult("paymentOption", option))
        } else {
          presentPaymentSheetPromise?.resolve(WritableNativeMap())
        }
      }
      else if (intent.action == ON_INIT_PAYMENT_SHEET) {
        initPaymentSheetPromise?.resolve(WritableNativeMap())
      } else if (intent.action == ON_CONFIGURE_FLOW_CONTROLLER) {
        val label = intent.extras?.getString("label")
        val image = intent.extras?.getString("image")

        if (label != null && image != null) {
          val option: WritableMap = WritableNativeMap()
          option.putString("label", label)
          option.putString("image", image)
          initPaymentSheetPromise?.resolve(createResult("paymentOption", option))
        } else {
          initPaymentSheetPromise?.resolve(WritableNativeMap())
        }
      } else if (intent.action == ON_GOOGLE_PAY_FRAGMENT_CREATED) {
        payWithGooglePromise?.resolve(WritableNativeMap())
      }
    }
  }

  @ReactMethod
  fun initialise(params: ReadableMap, promise: Promise) {
    val publishableKey = getValOr(params, "publishableKey", null) as String
    val appInfo = getMapOrNull(params, "appInfo") as ReadableMap
    this.stripeAccountId = getValOr(params, "stripeAccountId", null)
    val urlScheme = getValOr(params, "urlScheme", null)
    val setUrlSchemeOnAndroid = getBooleanOrFalse(params, "setUrlSchemeOnAndroid")
    this.urlScheme = if (setUrlSchemeOnAndroid) urlScheme else null

    getMapOrNull(params, "threeDSecureParams")?.let {
      configure3dSecure(it)
    }

    this.publishableKey = publishableKey

    val name = getValOr(appInfo, "name", "") as String
    val partnerId = getValOr(appInfo, "partnerId", "")
    val version = getValOr(appInfo, "version", "")

    val url = getValOr(appInfo, "url", "")
    Stripe.appInfo = AppInfo.create(name, version, url, partnerId)
    stripe = Stripe(reactApplicationContext, publishableKey, stripeAccountId)

    PaymentConfiguration.init(reactApplicationContext, publishableKey, stripeAccountId)

    this.currentActivity?.registerReceiver(mPaymentSheetReceiver, IntentFilter(ON_PAYMENT_RESULT_ACTION));
    this.currentActivity?.registerReceiver(mPaymentSheetReceiver, IntentFilter(ON_PAYMENT_OPTION_ACTION));
    this.currentActivity?.registerReceiver(mPaymentSheetReceiver, IntentFilter(ON_CONFIGURE_FLOW_CONTROLLER));
    this.currentActivity?.registerReceiver(mPaymentSheetReceiver, IntentFilter(ON_PAYMENT_SHEET_FRAGMENT_CREATED));
    this.currentActivity?.registerReceiver(mPaymentSheetReceiver, IntentFilter(ON_INIT_PAYMENT_SHEET));
    this.currentActivity?.registerReceiver(mPaymentSheetReceiver, IntentFilter(
      ON_GOOGLE_PAY_FRAGMENT_CREATED));

    promise.resolve(null)
  }

  @ReactMethod
  fun initPaymentSheet(params: ReadableMap, promise: Promise) {
    val activity = currentActivity as AppCompatActivity?

    if (activity == null) {
      promise.resolve(createError("Failed", "Activity doesn't exist"))
      return
    }
    val customFlow = getBooleanOrNull(params, "customFlow") ?: false
    val customerId = getValOr(params, "customerId")
    val customerEphemeralKeySecret = getValOr(params, "customerEphemeralKeySecret")
    val paymentIntentClientSecret = getValOr(params, "paymentIntentClientSecret")
    val setupIntentClientSecret = getValOr(params, "setupIntentClientSecret")
    val merchantDisplayName = getValOr(params, "merchantDisplayName")
    val countryCode = getValOr(params, "merchantCountryCode")
    val testEnv = getBooleanOrNull(params, "testEnv") ?: false
    val googlePay = getBooleanOrNull(params, "googlePay") ?: false

    this.initPaymentSheetPromise = promise

    val fragment = PaymentSheetFragment().also {
      val bundle = Bundle()
      bundle.putString("customerId", customerId)
      bundle.putString("customerEphemeralKeySecret", customerEphemeralKeySecret)
      bundle.putString("paymentIntentClientSecret", paymentIntentClientSecret)
      bundle.putString("setupIntentClientSecret", setupIntentClientSecret)
      bundle.putString("merchantDisplayName", merchantDisplayName)
      bundle.putString("countryCode", countryCode)
      bundle.putBoolean("customFlow", customFlow)
      bundle.putBoolean("testEnv", testEnv)
      bundle.putBoolean("googlePay", googlePay)

      it.arguments = bundle
    }
    activity.supportFragmentManager.beginTransaction()
      .add(fragment, "payment_sheet_launch_fragment")
      .commit()
  }

  @ReactMethod
  fun presentPaymentSheet(params: ReadableMap?, promise: Promise) {
    val confirmPayment = getBooleanOrNull(params, "confirmPayment")
    this.presentPaymentSheetPromise = promise
    if (confirmPayment == false) {
      paymentSheetFragment?.presentPaymentOptions()
    } else {
      paymentSheetFragment?.present()
    }
  }

  @ReactMethod
  fun confirmPaymentSheetPayment(promise: Promise) {
    this.confirmPaymentSheetPaymentPromise = promise
    paymentSheetFragment?.confirmPayment()
  }

  private fun payWithFpx() {
    AddPaymentMethodActivityStarter(currentActivity as AppCompatActivity)
      .startForResult(AddPaymentMethodActivityStarter.Args.Builder()
        .setPaymentMethodType(PaymentMethod.Type.Fpx)
        .build()
      )
  }

  private fun onFpxPaymentMethodResult(result: AddPaymentMethodActivityStarter.Result) {
    when (result) {
      is AddPaymentMethodActivityStarter.Result.Success -> {
        val activity = currentActivity as ComponentActivity

        stripe.confirmPayment(activity,
          ConfirmPaymentIntentParams.createWithPaymentMethodId(
            result.paymentMethod.id!!,
            confirmPaymentClientSecret!!,
          ));
      }
      is AddPaymentMethodActivityStarter.Result.Failure -> {
        confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), result.exception))
      }
      is AddPaymentMethodActivityStarter.Result.Canceled -> {
        confirmPromise?.resolve(createError(ConfirmPaymentErrorType.Canceled.toString(), "The payment has been canceled"))
      }
    }
    this.confirmPaymentClientSecret = null
  }

  @ReactMethod
  fun createPaymentMethod(data: ReadableMap, options: ReadableMap, promise: Promise) {
    val billingDetailsParams = mapToBillingDetails(getMapOrNull(data, "billingDetails"))
    val instance = cardFieldManager.getCardViewInstance()
    val cardParams = instance?.cardParams ?: run {
      promise.reject("Failed", "Card details not complete")
      return
    }
    val paymentMethodCreateParams = PaymentMethodCreateParams.create(cardParams, billingDetailsParams)
    stripe.createPaymentMethod(
      paymentMethodCreateParams,
      callback = object : ApiResultCallback<PaymentMethod> {
        override fun onError(error: Exception) {
          promise.resolve(createError("Failed", error))
        }

        override fun onSuccess(result: PaymentMethod) {
          val paymentMethodMap: WritableMap = mapFromPaymentMethod(result)
          promise.resolve(createResult("paymentMethod", paymentMethodMap))
        }
      })
  }

  @ReactMethod
  fun createToken(params: ReadableMap, promise: Promise) {
    val type = getValOr(params, "type", null)?.let {
      if (it != "Card") {
        promise.resolve(createError(CreateTokenErrorType.Failed.toString(), "$it type is not supported yet"))
        return
      }
    }
    val address = getMapOrNull(params, "address")
    val instance = cardFieldManager.getCardViewInstance()
    val cardParams = instance?.cardParams?.toParamMap() ?: run {
      promise.resolve(createError(CreateTokenErrorType.Failed.toString(), "Card details not complete"))
      return
    }

    val params = CardParams(
      number = cardParams["number"] as String,
      expMonth = cardParams["exp_month"] as Int,
      expYear = cardParams["exp_year"] as Int,
      cvc = cardParams["cvc"] as String,
      address = mapToAddress(address),
      name = getValOr(params, "name", null)
    )
    runBlocking {
      try {
        val token = stripe.createCardToken(
          cardParams = params,
          stripeAccountId = stripeAccountId
        )
        promise.resolve(createResult("token", mapFromToken(token)))
      } catch (e: Exception) {
        promise.resolve(createError(CreateTokenErrorType.Failed.toString(), e.message))
      }
    }
  }

  @ReactMethod
  fun createTokenForCVCUpdate(cvc: String, promise: Promise) {
    stripe.createCvcUpdateToken(
      cvc,
      callback = object : ApiResultCallback<Token> {
        override fun onSuccess(result: Token) {
          val tokenId = result.id
          val res = WritableNativeMap()
          res.putString("tokenId", tokenId)
          promise.resolve(res)
        }

        override fun onError(error: Exception) {
          promise.resolve(createError("Failed", error))
        }
      }
    )
  }

  @ReactMethod
  fun handleCardAction(paymentIntentClientSecret: String, promise: Promise) {
    val activity = currentActivity as ComponentActivity
    if (activity != null) {
      handleCardActionPromise = promise
      stripe.handleNextActionForPayment(activity, paymentIntentClientSecret)
    }
  }

  @ReactMethod
  fun confirmPayment(paymentIntentClientSecret: String, params: ReadableMap, options: ReadableMap, promise: Promise) {
    confirmPromise = promise
    confirmPaymentClientSecret = paymentIntentClientSecret

    val instance = cardFieldManager.getCardViewInstance()
    val cardParams = instance?.cardParams

    val paymentMethodType = getValOr(params, "type")?.let { mapToPaymentMethodType(it) } ?: run {
      promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), "You must provide paymentMethodType"))
      return
    }

    val testOfflineBank = getBooleanOrFalse(params, "testOfflineBank")

    if (paymentMethodType == PaymentMethod.Type.Fpx && !testOfflineBank) {
      payWithFpx()
      return
    }

    val factory = PaymentMethodCreateParamsFactory(paymentIntentClientSecret, params, cardParams)

    try {
      val confirmParams = factory.createConfirmParams(paymentMethodType)
      confirmParams.shipping = mapToShippingDetails(getMapOrNull(params, "shippingDetails"))
      val activity = currentActivity as ComponentActivity
      stripe.confirmPayment(activity, confirmParams)
    } catch (error: PaymentMethodCreateParamsException) {
      promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), error))
    }
  }

  @ReactMethod
  fun retrievePaymentIntent(clientSecret: String, promise: Promise) {
    AsyncTask.execute {
      val paymentIntent = stripe.retrievePaymentIntentSynchronous(clientSecret)
      paymentIntent?.let {
        promise.resolve(createResult("paymentIntent", mapFromPaymentIntentResult(it)))
      } ?: run {
        promise.resolve(createError(RetrievePaymentIntentErrorType.Unknown.toString(), "Failed to retrieve the PaymentIntent"))
      }
    }
  }

  @ReactMethod
  fun retrieveSetupIntent(clientSecret: String, promise: Promise) {
    AsyncTask.execute {
      val setupIntent = stripe.retrieveSetupIntentSynchronous(clientSecret)
      setupIntent?.let {
        promise.resolve(createResult("setupIntent", mapFromSetupIntentResult(it)))
      } ?: run {
        promise.resolve(createError(RetrieveSetupIntentErrorType.Unknown.toString(), "Failed to retrieve the SetupIntent"))
      }
    }
  }

  @ReactMethod
  fun confirmSetupIntent(setupIntentClientSecret: String, params: ReadableMap, options: ReadableMap, promise: Promise) {
    confirmSetupIntentPromise = promise

    val instance = cardFieldManager.getCardViewInstance()
    val cardParams = instance?.cardParams

    val paymentMethodType = getValOr(params, "type")?.let { mapToPaymentMethodType(it) } ?: run {
      promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), "You must provide paymentMethodType"))
      return
    }

    val factory = PaymentMethodCreateParamsFactory(setupIntentClientSecret, params, cardParams)

    try {
      val confirmParams = factory.createSetupParams(paymentMethodType)
      val activity = currentActivity as ComponentActivity
      stripe.confirmSetupIntent(activity, confirmParams)
    } catch (error: PaymentMethodCreateParamsException) {
      promise.resolve(createError(ConfirmPaymentErrorType.Failed.toString(), error))
    }
  }

  @ReactMethod
  fun initGooglePay(params: ReadableMap, promise: Promise) {
    paymentsClient = Wallet.getPaymentsClient(
      currentActivity!!,
      Wallet.WalletOptions.Builder()
        .setEnvironment(WalletConstants.ENVIRONMENT_TEST)
        .build()
    )

    val request = createIsReadyToPayRequest()
    paymentsClient!!.isReadyToPay(request)
      .addOnCompleteListener { task ->
        if (task.isSuccessful) {
          val map = Arguments.createMap()
          map.putBoolean("isReady", true)
          promise.resolve(createResult("googlePay", map))
        } else {
          val map = Arguments.createMap()
          map.putBoolean("isReady", false)
          promise.resolve(createResult("googlePay", map))
        }
      }
  }

  @ReactMethod
  fun payWithGoogle(params: ReadableMap, promise: Promise) {
    val activity = currentActivity as ComponentActivity
    val paymentIntentClientSecret = getValOr(params, "paymentIntentClientSecret", null)

    paymentIntentClientSecret ?: run {
      promise.resolve(createError(GooglePayErrorType.Failed.toString(), "you must provide clientSecret"))
      return
    }

    this.paymentIntentClientSecret = paymentIntentClientSecret

    val reqData = createPaymentDataRequest()
    this.payWithGooglePromise = promise

    AutoResolveHelper.resolveTask(
      paymentsClient?.loadPaymentData(reqData!!),
      activity,
      LOAD_PAYMENT_DATA_REQUEST_CODE
    );
  }

//  @ReactMethod
//  fun initGooglePay(params: ReadableMap, promise: Promise) {
//    val activity = currentActivity as AppCompatActivity?
//
//    if (activity == null) {
//      promise.resolve(createError("Failed", "Activity doesn't exist"))
//      return
//    }
//    val testEnv = getBooleanOrNull(params, "testEnv") ?: false
//
//
//    val fragment = GooglePayFragment().also {
//      val bundle = Bundle()
//      bundle.putBoolean("testEnv", testEnv)
//
//      it.arguments = bundle
//    }
//    activity.supportFragmentManager.beginTransaction()
//      .add(fragment, "google_pay_launch_fragment")
//      .commit()
//  }

  private fun onGooglePayResult(data: Intent) {
    val paymentData = PaymentData.getFromIntent(data) ?: return
    val paymentMethodCreateParams =
      PaymentMethodCreateParams.createFromGooglePay(
        JSONObject(paymentData.toJson())
      )

    val activity = currentActivity as ComponentActivity
    activity.lifecycleScope.launch {
      runCatching {
        stripe.createPaymentMethod(paymentMethodCreateParams)
      }.fold(
        onSuccess = { result ->
          val confirmParams = ConfirmPaymentIntentParams
            .createWithPaymentMethodId(
              paymentMethodId = result.id.orEmpty(),
              clientSecret = paymentIntentClientSecret!!
            )
          stripe.confirmPayment(
            activity,
            confirmPaymentIntentParams = confirmParams
          )
        },
        onFailure = {
          payWithGooglePromise?.resolve(createError(GooglePayErrorType.Failed.toString(), it))
        }
      )
    }
  }

  @Throws(JSONException::class)
  private fun createIsReadyToPayRequest(): IsReadyToPayRequest {
    val allowedAuthMethods = JSONArray()
    allowedAuthMethods.put("PAN_ONLY")
    allowedAuthMethods.put("CRYPTOGRAM_3DS")
    val allowedCardNetworks = JSONArray()
    allowedCardNetworks.put("AMEX")
    allowedCardNetworks.put("DISCOVER")
    allowedCardNetworks.put("MASTERCARD")
    allowedCardNetworks.put("VISA")
    val cardParameters = JSONObject()
    cardParameters.put("allowedAuthMethods", allowedAuthMethods)
    cardParameters.put("allowedCardNetworks", allowedCardNetworks)
    val cardPaymentMethod = JSONObject()
    cardPaymentMethod.put("type", "CARD")
    cardPaymentMethod.put("parameters", cardParameters)
    val allowedPaymentMethods = JSONArray()
    allowedPaymentMethods.put(cardPaymentMethod)
    val isReadyToPayRequestJson = JSONObject()
    isReadyToPayRequestJson.put("apiVersion", 2)
    isReadyToPayRequestJson.put("apiVersionMinor", 0)
    isReadyToPayRequestJson.put("allowedPaymentMethods", allowedPaymentMethods)
    return IsReadyToPayRequest.fromJson(isReadyToPayRequestJson.toString())
  }

  private fun createPaymentDataRequest(): PaymentDataRequest? {
    val activity = currentActivity as ComponentActivity

    val tokenizationSpec: JSONObject = GooglePayConfig(activity).tokenizationSpecification
    val cardPaymentMethod = JSONObject()
      .put("type", "CARD")
      .put(
        "parameters",
        JSONObject()
          .put(
            "allowedAuthMethods", JSONArray()
              .put("PAN_ONLY")
              .put("CRYPTOGRAM_3DS")
          )
          .put(
            "allowedCardNetworks",
            JSONArray()
              .put("AMEX")
              .put("DISCOVER")
              .put("MASTERCARD")
              .put("VISA")
          ) // require billing address
          .put("billingAddressRequired", true)
          .put(
            "billingAddressParameters",
            JSONObject() // require full billing address
              .put("format", "MIN") // require phone number
              .put("phoneNumberRequired", true)
          )
      )
      .put("tokenizationSpecification", tokenizationSpec)

    // create PaymentDataRequest
    val paymentDataRequest: String = JSONObject()
      .put("apiVersion", 2)
      .put("apiVersionMinor", 0)
      .put(
        "allowedPaymentMethods",
        JSONArray().put(cardPaymentMethod)
      )
      .put(
        "transactionInfo", JSONObject()
          .put("totalPrice", "10.00")
          .put("totalPriceStatus", "FINAL")
          .put("currencyCode", "USD")
      )
      .put(
        "merchantInfo", JSONObject()
          .put("merchantName", "Example Merchant")
      ) // require email address
      .put("emailRequired", true)
      .toString()
    return PaymentDataRequest.fromJson(paymentDataRequest)
  }

  /// Check paymentIntent.nextAction is voucher-based payment method.
  /// If it's voucher-based, the paymentIntent status stays in requiresAction until the voucher is paid or expired.
  /// Currently only OXXO payment is voucher-based.
  private fun isPaymentIntentNextActionVoucherBased(nextAction: StripeIntent.NextActionType?): Boolean {
    nextAction?.let {
      return it == StripeIntent.NextActionType.DisplayOxxoDetails
    }
    return false
  }
}
