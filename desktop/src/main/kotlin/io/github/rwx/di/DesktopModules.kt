package io.github.rwx.di

import io.github.rwx.*
import io.github.rwx.kool.KoolDesktopGameSession
import io.github.rwx.p2p.DesktopWebRtcTunnelProxy
import io.github.rwx.p2p.WebRtcTunnelProxy
import org.koin.dsl.module

val desktopModule = module {
    WebRtcTunnelProxy.registerFactory { config -> DesktopWebRtcTunnelProxy(config) }
    single<PlatformBridge> { DesktopPlatformBridge() }
    single<PlatformStorage> { get<PlatformBridge>().storage }
    single<PreferenceStorage> { get<PlatformBridge>().preferenceStorage }
    single<AppMetadata> { get<PlatformBridge>().appMetadata }
    single<AppLogger> { get<PlatformBridge>().logger }
    single<CrashReporter> { get<PlatformBridge>().crashReporter }
    single { KoolDesktopGameSession(storage = get()) }
    single<io.github.rwx.session.GameSession> { get<KoolDesktopGameSession>() }
}
