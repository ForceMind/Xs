#!/usr/bin/env python3
"""Host control-flow regression for destructive/privileged emulator pressure commands.

The real driver/main/run/log/report code executes against a stateful fake subprocess.
No adb binary, device, disk allocation or privilege change is used by these tests.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

DRIVER=Path(__file__).with_name("test-emulator-low-space.py")
spec=importlib.util.spec_from_file_location("xs_low_space_driver_host_test",DRIVER)
driver=importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
MIB=1024*1024


class AdbModel:
    """Model command exit status and partial side effects, not driver branch decisions."""
    def __init__(self,**options):
        self.options=options;self.calls=[];self.uid=options.get("initial_uid","2000")
        self.paths=set();self.allocated=False;self.low_ran=False;self.reset_failed=False
    def result(self,command,stdout="",stderr="",code=0):
        return subprocess.CompletedProcess(command,code,stdout,stderr)
    def path(self,value):
        if not re.fullmatch(r"/data/local/tmp/xs-disk-pressure-fixture-[0-9a-f]{32}",value):
            raise AssertionError("A destructive operation escaped the single generated fixture path")
        self.paths.add(value)
        if len(self.paths)!=1:raise AssertionError("One run must never touch multiple pressure files")
    def run(self,command,**kwargs):
        if command[0]!="adb":raise AssertionError("Unexpected real subprocess")
        args=list(command[1:]);self.calls.append(args)
        if args==["get-serialno"]:return self.result(command,self.options.get("serial","emulator-5554")+"\n")
        if args[:2]==["shell","getprop"]:
            values={"ro.kernel.qemu":self.options.get("qemu","1"),"ro.build.version.sdk":self.options.get("sdk","26"),
                    "ro.build.type":self.options.get("build_type","userdebug"),"ro.debuggable":self.options.get("debuggable","1")}
            if args[2] not in values:raise AssertionError("Unexpected guard property")
            return self.result(command,values[args[2]]+"\n")
        if args==["shell","id","-u"]:return self.result(command,self.uid+"\n")
        if args[:3]==["shell","am","instrument"]:
            phase=args[args.index("xs_phase")+1]
            if phase=="seed":
                return self.result(command,"INSTRUMENTATION_STATUS: xs_seed_available_bytes="+str(2560*MIB)+"\nINSTRUMENTATION_STATUS: xs_seed_app_uid="+str(self.options.get("seed_uid",10123))+"\nOK (1 test)\n")
            if phase!="low-space":raise AssertionError("Unexpected native fixture phase")
            self.low_ran=True
            return self.result(command,"INSTRUMENTATION_STATUS: xs_available_bytes="+str(self.options.get("low_bytes",29*MIB))+"\nINSTRUMENTATION_STATUS: xs_low_space_app_uid="+str(self.options.get("low_uid",10123))+"\n"+("FAILURES!!!\n" if self.options.get("native_failure") else "OK (1 test)\n"))
        if args==["shell","df","-k","/data"]:
            return self.result(command,"Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/data 8388608 1000000 3145728 12% /data\n")
        if args[:2]==["shell","fallocate"]:
            self.path(args[-1]);self.allocated=True
            if self.options.get("quota",True) and self.uid!="0":return self.result(command,stderr="fallocate: unsupported length\n",code=1)
            return self.result(command)
        if args[:2]==["shell","dd"]:
            target=next(item[3:] for item in args if item.startswith("of="));self.path(target);self.allocated=True
            if self.uid!="0" and self.options.get("quota",True):return self.result(command,stderr=self.options.get("dd_error","dd: Quota exceeded\n"),code=1)
            return self.result(command,"bytes copied\n")
        if args[:3]==["shell","rm","-f"]:
            self.path(args[-1])
            if self.options.get("reset_failure") and not self.reset_failed:
                self.reset_failed=True;return self.result(command,stderr="rm: reset failed\n",code=1)
            if self.low_ran and self.options.get("cleanup_failure"):return self.result(command,stderr="rm: cleanup failed\n",code=1)
            if not (self.low_ran and self.options.get("cleanup_leaves_file")):self.allocated=False
            return self.result(command)
        if args[:4]==["shell","test","!","-e"]:
            self.path(args[-1]);return self.result(command,code=int(self.allocated))
        if args==["root"]:
            # Even a failed restart may already have changed the daemon's identity.
            self.uid="0"
            if self.options.get("root_failure"):return self.result(command,stderr="adbd restart interrupted\n",code=1)
            return self.result(command,"restarting adbd as root\n")
        if args==["unroot"]:
            if self.options.get("unroot_failure"):return self.result(command,stderr="unroot failed\n",code=1)
            self.uid="2000";return self.result(command,"restarting adbd as non root\n")
        if args==["wait-for-device"]:return self.result(command)
        raise AssertionError("Unexpected adb command: "+repr(args))


class LowSpaceDriverTests(unittest.TestCase):
    def execute(self,model,*,github=True,stale_quota=False):
        with tempfile.TemporaryDirectory(prefix="xs-low-space-host-") as directory:
            out=Path(directory)
            if stale_quota:(out/"allocate-pressure-dd.txt").write_text("old invocation: Quota exceeded\n")
            captured=io.StringIO();failure=None
            with patch.object(driver,"OUT",out),patch.object(driver.subprocess,"run",side_effect=model.run),patch.dict(os.environ,{"GITHUB_ACTIONS":"true" if github else "false"}),contextlib.redirect_stdout(captured):
                try:driver.main()
                except (RuntimeError,subprocess.TimeoutExpired) as error:failure=error
            return json.loads((out/"summary.json").read_text()),captured.getvalue(),failure
    def failed(self,result):
        report,stdout,error=result;self.assertIsNotNone(error);self.assertEqual("FAIL",report["status"]);self.assertNotIn("PASS",stdout);return report
    def no_allocation_or_privilege(self,model):
        self.assertFalse(model.paths)
        self.assertFalse(any(call==["root"] or call==["unroot"] or call[:2] in (["shell","fallocate"],["shell","dd"],["shell","rm"]) for call in model.calls))
    def restored_and_clean(self,model):
        self.assertFalse(model.allocated);self.assertEqual("2000",model.uid)
        self.assertEqual(1,model.calls.count(["unroot"]))
        self.assertTrue(model.paths)
    def test_global_guards_reject_before_any_allocation_or_privilege(self):
        for options,github in [({},False),({"serial":"physical-fixture"},True),({"qemu":"0"},True),({"initial_uid":"0"},True)]:
            with self.subTest(options=options,github=github):
                model=AdbModel(**options);self.failed(self.execute(model,github=github));self.no_allocation_or_privilege(model)
                if not github:self.assertEqual([],model.calls)
    def test_ordinary_allocation_never_requests_root(self):
        model=AdbModel(quota=False);report,stdout,error=self.execute(model)
        self.assertIsNone(error);self.assertEqual("PASS",report["status"]);self.assertIn("PASS",stdout)
        self.assertNotIn(["root"],model.calls);self.assertNotIn(["unroot"],model.calls);self.assertFalse(model.allocated)
    def test_current_quota_allows_only_scoped_allocator_then_ordinary_native_check(self):
        for sdk in ("26","27"):
            with self.subTest(sdk=sdk):
                model=AdbModel(sdk=sdk);report,stdout,error=self.execute(model)
                self.assertIsNone(error);self.assertEqual("PASS",report["status"]);self.assertIn("PASS",stdout);self.restored_and_clean(model)
                self.assertEqual(10123,report["native_actor_uid"]);self.assertEqual(0,report["allocator_uid_during"]);self.assertEqual(2000,report["allocator_uid_restored"])
                self.assertGreater(report["native_available_bytes"],0);self.assertLess(report["native_available_bytes"],40*MIB)
                first_dd=next(i for i,call in enumerate(model.calls) if call[:2]==["shell","dd"])
                root=model.calls.index(["root"]);low=next(i for i,call in enumerate(model.calls) if "low-space" in call)
                self.assertLess(first_dd,root);self.assertLess(root,low);self.assertLess(low,model.calls.index(["unroot"]))
    def test_quota_on_unapproved_platform_or_build_never_requests_root(self):
        for options in ({"sdk":"30"},{"build_type":"user"},{"debuggable":"0"}):
            with self.subTest(options=options):
                model=AdbModel(**options);self.failed(self.execute(model));self.assertNotIn(["root"],model.calls);self.assertNotIn(["unroot"],model.calls);self.assertFalse(model.allocated)
    def test_stale_quota_log_cannot_authorize_a_different_current_failure(self):
        model=AdbModel(reset_failure=True);self.failed(self.execute(model,stale_quota=True))
        self.assertFalse(any(call[:2]==["shell","dd"] for call in model.calls));self.assertNotIn(["root"],model.calls);self.assertNotIn(["unroot"],model.calls);self.assertFalse(model.allocated)
    def test_non_quota_dd_error_does_not_authorize_root(self):
        model=AdbModel(dd_error="dd: No space left on device\n");self.failed(self.execute(model));self.assertNotIn(["root"],model.calls);self.assertFalse(model.allocated)
    def test_mismatched_native_uid_or_unproven_pressure_fails_and_restores(self):
        for options in ({"low_uid":0},{"low_uid":10124},{"low_bytes":0},{"low_bytes":40*MIB},{"native_failure":True}):
            with self.subTest(options=options):
                model=AdbModel(**options);self.failed(self.execute(model));self.restored_and_clean(model)
    def test_root_command_failure_with_partial_effect_still_cleans_and_unroots(self):
        model=AdbModel(root_failure=True);report=self.failed(self.execute(model));self.restored_and_clean(model)
        self.assertFalse(model.low_ran);self.assertEqual(2000,report["allocator_uid_restored"])
    def test_cleanup_failure_or_remaining_file_can_never_report_pass(self):
        for options in ({"cleanup_failure":True},{"cleanup_leaves_file":True}):
            with self.subTest(options=options):
                model=AdbModel(**options);report=self.failed(self.execute(model));self.assertTrue(report["cleanup_failed"])
                self.assertEqual("2000",model.uid);self.assertEqual(2000,report["allocator_uid_restored"]);self.assertTrue(model.allocated)
    def test_failed_privilege_restore_can_never_report_pass(self):
        model=AdbModel(unroot_failure=True);report=self.failed(self.execute(model))
        self.assertTrue(report["privilege_restore_failed"]);self.assertFalse(model.allocated);self.assertEqual("0",model.uid)


if __name__=="__main__":unittest.main(verbosity=2)
