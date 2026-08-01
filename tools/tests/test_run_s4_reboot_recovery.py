import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "verify-s4-reboot-recovery.ps1"
TEST = (
    ROOT
    / "app"
    / "src"
    / "androidTest"
    / "java"
    / "com"
    / "noteapp"
    / "SessionRebootRecoveryInstrumentedTest.kt"
)


class RebootRecoveryHarnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = SCRIPT.read_text(encoding="utf-8")
        cls.instrumentation = TEST.read_text(encoding="utf-8")

    def test_requires_explicit_execution_and_real_reboot(self):
        self.assertIn("if (-not $Execute)", self.script)
        self.assertIn("& $Adb -s $Serial reboot", self.script)
        self.assertIn("DEVICE_DID_NOT_REBOOT", self.script)

    def test_refuses_to_run_while_recording_service_is_active(self):
        self.assertIn("ACTIVE_RECORDING_SERVICE_REFUSES_REBOOT_AUDIT", self.script)
        self.assertIn("AudioCaptureService", self.script)

    def test_never_clears_or_uninstalls_production_app(self):
        lowered = self.script.lower()
        self.assertNotIn('"pm", "clear"', lowered)
        self.assertNotIn('"uninstall", $packagename', lowered)
        self.assertIn('"install", "-r", "-t", $appapk', lowered)

    def test_fixture_is_isolated_and_cleaned(self):
        self.assertIn('files/sprint4-reboot-recovery-audit', self.script)
        self.assertIn("verifyRecoveryAfterRebootIsIdempotentAndCleanup", self.script)
        self.assertIn("deleteAuditRoot(root)", self.instrumentation)
        self.assertIn("REBOOT_RECOVERY_AUDIT_ROOT_REMAINS", self.script)

    def test_audit_does_not_record_or_transcribe(self):
        self.assertIn("recordingStartedByAudit = $false", self.script)
        self.assertIn("transcriptionStartedByAudit = $false", self.script)
        self.assertNotIn("AudioRecord", self.instrumentation)
        self.assertNotIn("transcribe", self.instrumentation.lower())

    def test_recovery_is_checked_before_and_after_checkpoint_commit(self):
        self.assertIn("repeatedBeforeCheckpoint", self.instrumentation)
        self.assertIn("repeatedAfterCheckpoint", self.instrumentation)
        self.assertIn("RECOVERY_REWROTE_AUTHENTICATED_PREFIX", self.instrumentation)

    def test_production_inventory_is_preserved_and_reaudited(self):
        self.assertIn("PRODUCTION_ARTIFACT_INVENTORY_CHANGED_DURING_REBOOT_AUDIT", self.script)
        self.assertIn("verify-s4-encrypted-artifacts.ps1", self.script)
        self.assertIn("productionInventoryPreserved = $true", self.script)


if __name__ == "__main__":
    unittest.main()
