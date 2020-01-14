package com.nextcloud.talk.newarch.features.account.loginentry

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nextcloud.talk.models.LoginData
import com.nextcloud.talk.models.json.capabilities.CapabilitiesOverall
import com.nextcloud.talk.models.json.push.PushConfiguration
import com.nextcloud.talk.models.json.push.PushConfigurationState
import com.nextcloud.talk.models.json.push.PushConfigurationStateWrapper
import com.nextcloud.talk.models.json.push.PushRegistrationOverall
import com.nextcloud.talk.models.json.signaling.settings.SignalingSettingsOverall
import com.nextcloud.talk.models.json.userprofile.UserProfileOverall
import com.nextcloud.talk.newarch.conversationsList.mvp.BaseViewModel
import com.nextcloud.talk.newarch.data.model.ErrorModel
import com.nextcloud.talk.newarch.domain.repository.offline.UsersRepository
import com.nextcloud.talk.newarch.domain.usecases.*
import com.nextcloud.talk.newarch.domain.usecases.base.UseCaseResponse
import com.nextcloud.talk.newarch.local.models.UserNgEntity
import com.nextcloud.talk.utils.PushUtils
import com.nextcloud.talk.utils.preferences.AppPreferences
import kotlinx.coroutines.launch
import org.koin.core.parameter.parametersOf
import java.net.URLDecoder

class LoginEntryViewModel constructor(
        application: Application,
        private val getProfileUseCase: GetProfileUseCase,
        private val getCapabilitiesUseCase: GetCapabilitiesUseCase,
        private val getSignalingSettingsUseCase: GetSignalingSettingsUseCase,
        private val registerPushWithServerUseCase: RegisterPushWithServerUseCase,
        private val registerPushWithProxyUseCase: RegisterPushWithProxyUseCase,
        private val appPreferences: AppPreferences,
        private val usersRepository: UsersRepository) :
        BaseViewModel<LoginEntryView>(application) {
    val state: MutableLiveData<LoginEntryStateWrapper> = MutableLiveData(LoginEntryStateWrapper(LoginEntryState.PENDING_CHECK, null))

    private var user = UserNgEntity(-1, "-1", "", "")
    private var updatingUser = false

    fun parseData(prefix: String, separator: String, data: String?) {
        viewModelScope.launch {
            if (data?.startsWith(prefix) == false) {
                state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.INVALID_PARSED_DATA))
                return@launch
            }

            data as String

            val loginData = LoginData()
            // format is xxx://login/server:xxx&user:xxx&password:xxx
            val dataWithoutPrefix = data.substring(prefix.length)
            val values = dataWithoutPrefix.split("&").toTypedArray()
            if (values.size != 3) {
                state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.INVALID_PARSED_DATA))
                return@launch
            }

            for (value in values) {
                when {
                    value.startsWith("user$separator") -> {
                        loginData.username = URLDecoder.decode(
                                value.substring("user$separator".length)
                        )
                    }
                    value.startsWith("password$separator") -> {
                        loginData.token = URLDecoder.decode(
                                value.substring("password$separator".length)
                        )
                    }
                    value.startsWith("server$separator") -> {
                        loginData.serverUrl = URLDecoder.decode(
                                value.substring("server$separator".length)
                        )
                    }
                    else -> {
                        // fail
                        state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.INVALID_PARSED_DATA))
                        return@launch
                    }
                }
            }

            if (!loginData.serverUrl.isNullOrEmpty() && !loginData.username.isNullOrEmpty() && !loginData.token.isNullOrEmpty()) {
                storeCredentialsOrVerify(loginData)
            } else {
                state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.INVALID_PARSED_DATA))
                return@launch
            }


        }
    }

    private suspend fun storeCredentialsOrVerify(loginData: LoginData) {
        // username and server url will be null here for sure because we do a check earlier in the process
        val userIfExists = usersRepository.getUserWithUsernameAndServer(loginData.username!!, loginData.serverUrl!!)
        if (userIfExists != null) {
            updatingUser = true
            user = userIfExists
            user.token = loginData.token
            usersRepository.updateUser(user)
            // complicated - we need to unregister, etc, etc, but not yet
            state.postValue(LoginEntryStateWrapper(LoginEntryState.OK, LoginEntryStateClarification.ACCOUNT_UPDATED))
        } else {
            getProfile(loginData)
        }
    }

    private fun getProfile(loginData: LoginData) {
        user.username = loginData.username!!
        user.baseUrl = loginData.serverUrl!!
        user.token = loginData.token
        getProfileUseCase.invoke(viewModelScope, parametersOf(user), object : UseCaseResponse<UserProfileOverall> {
            override suspend fun onSuccess(result: UserProfileOverall) {
                result.ocs.data.userId?.let { userId ->
                    user.displayName = result.ocs.data.displayName
                    user.userId = userId
                    getCapabilities()
                } ?: run {
                    state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.PROFILE_FETCH_FAILED))
                }
            }

            override suspend fun onError(errorModel: ErrorModel?) {
                state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.PROFILE_FETCH_FAILED))
            }
        })
    }

    private fun getCapabilities() {
        getCapabilitiesUseCase.invoke(viewModelScope, parametersOf(user.baseUrl), object : UseCaseResponse<CapabilitiesOverall> {
            override suspend fun onSuccess(result: CapabilitiesOverall) {
                user.capabilities = result.ocs.data.capabilities
                getSignalingSettings()
            }

            override suspend fun onError(errorModel: ErrorModel?) {
                state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.CAPABILITIES_FETCH_FAILED))
            }
        })
    }

    private fun getSignalingSettings() {
        getSignalingSettingsUseCase.invoke(viewModelScope, parametersOf(user), object : UseCaseResponse<SignalingSettingsOverall> {
            override suspend fun onSuccess(result: SignalingSettingsOverall) {
                user.signalingSettings = result.ocs.signalingSettings
                val pushConfiguration = PushConfiguration()
                val pushConfigurationStateWrapper = PushConfigurationStateWrapper(PushConfigurationState.PENDING, 0)
                pushConfiguration.pushConfigurationStateWrapper = pushConfigurationStateWrapper
                usersRepository.insertUser(user)
                registerForPush()
            }

            override suspend fun onError(errorModel: ErrorModel?) {
                state.postValue(LoginEntryStateWrapper(LoginEntryState.FAILED, LoginEntryStateClarification.SIGNALING_SETTINGS_FETCH_FAILED))
            }
        })
    }

    private suspend fun registerForPush() {
        val token = appPreferences.pushToken
        if (!token.isNullOrBlank()) {
            user.pushConfiguration?.pushToken = token
            usersRepository.updateUser(user)
            registerForPushWithServer(token)
        } else {
            state.postValue(LoginEntryStateWrapper(LoginEntryState.OK, LoginEntryStateClarification.PUSH_REGISTRATION_MISSING_TOKEN))
        }
    }

    private fun registerForPushWithServer(token: String) {
        val options = PushUtils(usersRepository).getMapForPushRegistrationWithServer(context, token)
        registerPushWithServerUseCase.invoke(viewModelScope, parametersOf(user, options), object : UseCaseResponse<PushRegistrationOverall> {
            override suspend fun onSuccess(result: PushRegistrationOverall) {
                user.pushConfiguration?.deviceIdentifier = result.ocs.data.deviceIdentifier
                user.pushConfiguration?.deviceIdentifierSignature = result.ocs.data.signature
                user.pushConfiguration?.userPublicKey = result.ocs.data.publicKey
                user.pushConfiguration?.pushConfigurationStateWrapper = PushConfigurationStateWrapper(PushConfigurationState.SERVER_REGISTRATION_DONE, null)
                usersRepository.updateUser(user)
                registerForPushWithProxy()
            }

            override suspend fun onError(errorModel: ErrorModel?) {
                user.pushConfiguration?.pushConfigurationStateWrapper?.pushConfigurationState = PushConfigurationState.FAILED_WITH_SERVER_REGISTRATION
                user.pushConfiguration?.pushConfigurationStateWrapper?.reason = errorModel?.code
                usersRepository.updateUser(user)
                state.postValue(LoginEntryStateWrapper(LoginEntryState.OK, LoginEntryStateClarification.PUSH_REGISTRATION_WITH_SERVER_FAILED))
            }
        })
    }

    private suspend fun registerForPushWithProxy() {
        val options = PushUtils(usersRepository).getMapForPushRegistrationWithServer(user)

        if (options != null) {
            registerPushWithProxyUseCase.invoke(viewModelScope, parametersOf(user, options), object : UseCaseResponse<Any> {
                override suspend fun onSuccess(result: Any) {
                    user.pushConfiguration?.pushConfigurationStateWrapper = PushConfigurationStateWrapper(PushConfigurationState.PROXY_REGISTRATION_DONE, null)
                    usersRepository.updateUser(user)
                    state.postValue(LoginEntryStateWrapper(LoginEntryState.OK, if (!updatingUser) LoginEntryStateClarification.ACCOUNT_CREATED else LoginEntryStateClarification.ACCOUNT_UPDATED))
                }

                override suspend fun onError(errorModel: ErrorModel?) {
                    user.pushConfiguration?.pushConfigurationStateWrapper?.pushConfigurationState = PushConfigurationState.FAILED_WITH_PROXY_REGISTRATION
                    user.pushConfiguration?.pushConfigurationStateWrapper?.reason = errorModel?.code
                    usersRepository.updateUser(user)
                    state.postValue(LoginEntryStateWrapper(LoginEntryState.OK, LoginEntryStateClarification.PUSH_REGISTRATION_WITH_PUSH_PROXY_FAILED))
                }
            })
        } else {
            user.pushConfiguration?.pushConfigurationStateWrapper?.pushConfigurationState = PushConfigurationState.FAILED_WITH_PROXY_REGISTRATION
            usersRepository.updateUser(user)
            state.postValue(LoginEntryStateWrapper(LoginEntryState.OK, LoginEntryStateClarification.PUSH_REGISTRATION_WITH_PUSH_PROXY_FAILED))
        }
    }

    private suspend fun setAdjustedUserAsActive() {
        if (user.id == -1L) {
            val adjustedUser = usersRepository.getUserWithUsernameAndServer(user.username, user.baseUrl)
            adjustedUser?.id?.let {
                usersRepository.setUserAsActiveWithId(it)
            }
        }
    }
}