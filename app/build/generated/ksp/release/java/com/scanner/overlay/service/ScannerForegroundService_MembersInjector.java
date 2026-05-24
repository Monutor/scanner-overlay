package com.scanner.overlay.service;

import android.content.SharedPreferences;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class ScannerForegroundService_MembersInjector implements MembersInjector<ScannerForegroundService> {
  private final Provider<SharedPreferences> prefsProvider;

  public ScannerForegroundService_MembersInjector(Provider<SharedPreferences> prefsProvider) {
    this.prefsProvider = prefsProvider;
  }

  public static MembersInjector<ScannerForegroundService> create(
      Provider<SharedPreferences> prefsProvider) {
    return new ScannerForegroundService_MembersInjector(prefsProvider);
  }

  @Override
  public void injectMembers(ScannerForegroundService instance) {
    injectPrefs(instance, prefsProvider.get());
  }

  @InjectedFieldSignature("com.scanner.overlay.service.ScannerForegroundService.prefs")
  public static void injectPrefs(ScannerForegroundService instance, SharedPreferences prefs) {
    instance.prefs = prefs;
  }
}
