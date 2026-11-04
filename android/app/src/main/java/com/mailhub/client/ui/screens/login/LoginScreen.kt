package com.mailhub.client.ui.screens.login

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailhub.client.AppContainer
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.common.pageMetaOf
import com.mailhub.client.ui.swiss.GeoCircle
import com.mailhub.client.ui.swiss.GeoSquare
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.IndexLabel
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.MetaLine
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SwissButton
import com.mailhub.client.ui.swiss.SwissButtonKind
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTextField
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.cols
import java.time.Year

/**
 * 登录页（无状态）。构图不对称：编号和元信息占顶部一行，黑色方块与红色圆错开摆放，
 * 大标题压在左侧，表单单栏排开，主按钮只占左侧 6 栏。
 * 提交中显示加载条；失败时出错的输入框画红色下划线，说明一律用黑色正文。
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    meta: PageMeta,
    onEvent: (LoginEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val serverFailure = state.failure?.takeIf { it.field == LoginField.Server }
    val passwordFailure = state.failure?.takeIf { it.field == LoginField.Password }
    val generalFailure = state.failure?.takeIf { it.field == null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        GridRow {
            IndexLabel(index = "00", modifier = Modifier.cols(1, 2))
            MetaLine(
                year = meta.year,
                place = meta.place,
                modifier = Modifier.cols(9, 4),
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xxl))
        GridRow {
            GeoSquare(
                span = 6,
                color = colors.foreground,
                modifier = Modifier.cols(1, 6),
            )
            // 圆的顶边比方块低一个段落间距，两者错开
            Column(modifier = Modifier.cols(8, 3)) {
                Spacer(modifier = Modifier.height(SwissSpace.Xxl))
                GeoCircle(span = 3, color = colors.accent)
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
        GridRow {
            SText(
                text = "MailHub",
                style = SwissType.title(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        GridRow {
            SText(
                text = "自部署邮箱 · 治理客户端",
                style = SwissType.caption(),
                modifier = Modifier.cols(1, 10),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xxxl))
        if (state.expiredNotice) {
            GridRow {
                SText(
                    text = "登录已过期，请重新登录",
                    style = SwissType.body(),
                    modifier = Modifier.cols(1, 10),
                    color = colors.foreground,
                )
            }
            Spacer(modifier = Modifier.height(SwissSpace.M))
        }
        GridRow {
            SwissTextField(
                value = state.serverUrl,
                onValueChange = { onEvent(LoginEvent.ServerChanged(it)) },
                label = "服务器地址",
                placeholder = "https://mail.example.com",
                isError = serverFailure != null,
                errorText = serverFailure?.message,
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.cols(1, 10),
            )
        }
        Spacer(modifier = Modifier.height(SwissSpace.M))
        GridRow {
            SwissTextField(
                value = state.password,
                onValueChange = { onEvent(LoginEvent.PasswordChanged(it)) },
                label = "管理密码",
                visualTransformation = PasswordVisualTransformation(),
                isError = passwordFailure != null,
                errorText = passwordFailure?.message,
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onEvent(LoginEvent.Submit) }),
                modifier = Modifier.cols(1, 10),
            )
        }
        if (generalFailure != null) {
            Spacer(modifier = Modifier.height(SwissSpace.M))
            GridRow {
                SText(
                    text = generalFailure.message,
                    style = SwissType.body(),
                    modifier = Modifier.cols(1, 10),
                    color = colors.foreground,
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xl))
        GridRow {
            SwissButton(
                text = "连接",
                onClick = { onEvent(LoginEvent.Submit) },
                kind = SwissButtonKind.Primary,
                enabled = !state.submitting,
                modifier = Modifier.cols(1, 6),
            )
        }
        if (state.submitting) {
            Spacer(modifier = Modifier.height(SwissSpace.S))
            LoadingRule()
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xxxl))
    }
}

/**
 * 登录页的有状态外层：取 ViewModel、收集状态，把事件转给它。
 * 设备名用厂商 + 机型；上次成功的服务器地址由页面层自己的偏好存储预填。
 */
@Composable
fun LoginScreenHost(
    container: AppContainer,
    expired: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: LoginViewModel = viewModel(
        factory = LoginViewModelFactory(
            login = { serverUrl, password, deviceName ->
                container.authRepository.login(serverUrl, password, deviceName)
            },
            lastServer = LastServerStoreProvider.of(context),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            expiredNotice = expired,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(expired) {
        viewModel.onEnter(expired)
    }
    LoginScreen(
        state = state,
        // 登录前拿不到 meta，页头地点显示兜底值
        meta = pageMetaOf(year = Year.now().value, meta = null),
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}
