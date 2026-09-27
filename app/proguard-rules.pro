# App R8 rules. Keep this list short and explain every rule.

# Kotlin rules: debug and verbose logs are stripped in release.
-assumenosideeffects class dev.folio.core.common.FolioLog {
    public void d(java.lang.String, java.lang.String);
    public void v(java.lang.String, java.lang.String);
}
