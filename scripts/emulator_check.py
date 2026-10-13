import os,subprocess,time,pathlib
root=pathlib.Path(__file__).resolve().parents[1];sdk=pathlib.Path(os.environ['ANDROID_HOME'])
out=root/'verification/emulator-api34';out.mkdir(parents=True,exist_ok=True)
env=dict(os.environ);env['LD_LIBRARY_PATH']='/tmp/forma-emulator-deps/usr/lib/x86_64-linux-gnu';env['JAVA_HOME']='/tmp/forma-tools/jdk-17.0.20.1+1'
img=sdk/'system-images/android-34/default/x86_64/system.img'
deadline=time.time()+300
while not img.exists() and time.time()<deadline:time.sleep(3)
if not img.exists():raise SystemExit('System image unavailable')
result=subprocess.run([str(sdk/'cmdline-tools/latest/bin/avdmanager'),'create','avd','--force','-n','forma-api34','-k','system-images;android-34;default;x86_64','--device','pixel_4'],input='no\n',text=True,env=env,capture_output=True)
(out/'avd.txt').write_text(result.stdout+result.stderr)
if result.returncode:raise SystemExit('AVD creation failed')
log=open(out/'emulator.log','w')
proc=subprocess.Popen([str(sdk/'emulator/emulator'),'-avd','forma-api34','-no-window','-no-audio','-no-boot-anim','-gpu','swiftshader','-accel','off','-no-snapshot','-memory','1536'],env=env,stdout=log,stderr=subprocess.STDOUT)
def adb(*args,timeout=60):return subprocess.run([str(sdk/'platform-tools/adb'),*args],capture_output=True,timeout=timeout)
def shell(*args):return adb('shell',*args).stdout.decode(errors='replace')
try:
 deadline=time.time()+420
 while time.time()<deadline:
  if proc.poll() is not None:raise RuntimeError('Emulator exited before boot')
  if shell('getprop','sys.boot_completed').strip()=='1':break
  time.sleep(3)
 else:raise RuntimeError('Software emulation boot timeout')
 print('API34 booted',flush=True)
 shell('wm','size','720x1600');shell('wm','density','320')
 for key in ['window_animation_scale','transition_animation_scale','animator_duration_scale']:shell('settings','put','global',key,'0')
 shell('settings','put','global','airplane_mode_on','1');shell('svc','wifi','disable');shell('svc','data','disable')
 apk=root/'app/build/outputs/apk/debug/app-debug.apk';test=root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
 deadline=time.time()+600
 while time.time()<deadline and not(apk.exists()and test.exists()):time.sleep(3)
 if not(apk.exists()and test.exists()):raise RuntimeError('APK artifacts unavailable')
 for p in [apk,test]:
  result=adb('install','-r',str(p),timeout=180);(out/(p.name+'.install.txt')).write_bytes(result.stdout+result.stderr)
  if result.returncode:raise RuntimeError('Install failed')
 shell('pm','grant','ru.forma.fitness','android.permission.POST_NOTIFICATIONS')
 result=adb('shell','am','instrument','-w','ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner',timeout=600)
 (out/'instrumentation.txt').write_bytes(result.stdout+result.stderr);print(result.stdout.decode(errors='replace')[-4000:],flush=True)
 shell('am','start','-n','ru.forma.fitness/ru.forma.app.MainActivity');time.sleep(3)
 (out/'screen.png').write_bytes(adb('exec-out','screencap','-p').stdout)
 shell('uiautomator','dump','/sdcard/window.xml');(out/'screen.xml').write_bytes(adb('exec-out','cat','/sdcard/window.xml').stdout)
 (out/'logcat.txt').write_bytes(adb('logcat','-d','-t','1200').stdout)
 if b'OK (1 test)' not in result.stdout:raise RuntimeError('Journey test failed')
 print('Offline emulator journey PASS',flush=True)
finally:
 adb('emu','kill');proc.terminate();log.close()
