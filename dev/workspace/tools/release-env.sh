# Sourced by the release commands. The release signing config expects an `upload` key from KEYSTORE_PATH,
# STORE_PASSWORD and KEY_PASSWORD. For local checks those point at the repository's public debug key under
# that alias, so a release build installs over the debug build without losing app data. Real release keys are
# never read or created here, and the variables exist only in the process that sourced this file.
release_test_keystore="$workspace/.tools/release-test.p12"
if [[ ! -f "$release_test_keystore" ]]; then
  keytool -importkeystore -noprompt \
    -srckeystore "$workspace/poseGuard/debug.keystore" -srcstorepass android -srcalias androiddebugkey -srckeypass android \
    -destkeystore "$release_test_keystore" -deststoretype PKCS12 -deststorepass android -destalias upload -destkeypass android \
    > "$workspace/artifacts/release-test-keystore.log" 2>&1
fi
export KEYSTORE_PATH="$release_test_keystore" STORE_PASSWORD=android KEY_PASSWORD=android
