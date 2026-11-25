package com.example.callhelper.nova;

import android.provider.AlarmClock;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.accessibilityservice.AccessibilityService;
import com.example.callhelper.InCallAccessibilityService;
import com.example.callhelper.CallNotificationListener;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.content.IntentFilter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the safe, built-in NOVA phone commands for Stage 2. */
public final class NovaCommandExecutor {
    private final Context context;

    public NovaCommandExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    public interface SequenceCallback { void onComplete(String response); }

    /** تنفيذ الخطوات بالتتابع مع مهلة انتقال بعد فتح التطبيقات أو تغيير الشاشة. */
    public void executeSequenceAsync(NovaCommand command, SequenceCallback callback) {
        String encoded = command == null ? "" : command.argument;
        String[] steps = encoded.split(";;");
        if (steps.length == 0 || steps.length > 4) {
            callback.onComplete("لم أتمكن من قراءة خطوات المهمة.");
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        runSequenceStep(steps, 0, new StringBuilder(), handler, callback);
    }

    private void runSequenceStep(String[] steps, int index, StringBuilder output,
                                 Handler handler, SequenceCallback callback) {
        if (index >= steps.length) {
            callback.onComplete(output.toString());
            return;
        }
        String step = steps[index];
        int split = step.indexOf('|');
        if (split <= 0) {
            callback.onComplete("توقفت المهمة لأن إحدى الخطوات غير واضحة.");
            return;
        }
        NovaCommand.Type type;
        try { type = NovaCommand.Type.valueOf(step.substring(0, split)); }
        catch (Exception e) { callback.onComplete("توقفت المهمة عند خطوة غير معروفة."); return; }
        if (type == NovaCommand.Type.MULTI_STEP || type == NovaCommand.Type.UNKNOWN
                || type == NovaCommand.Type.STOP_LISTENING || type == NovaCommand.Type.RESUME_LISTENING
                || type == NovaCommand.Type.CONFIRM_SEND) {
            callback.onComplete("تحتوي المهمة على خطوة لا يمكن تنفيذها ضمن سلسلة آمنة.");
            return;
        }
        String response = execute(new NovaCommand(type, "", step.substring(split + 1)));
        if (output.length() > 0) output.append(" ثم ");
        output.append(response);
        if (isExecutionFailure(response)) {
            callback.onComplete(output.toString());
            return;
        }
        long delay = (type == NovaCommand.Type.OPEN_APP || type == NovaCommand.Type.HOME
                || type == NovaCommand.Type.BACK || type == NovaCommand.Type.RECENTS
                || type == NovaCommand.Type.OPEN_NOTIFICATIONS || type == NovaCommand.Type.QUICK_SETTINGS) ? 1000L : 250L;
        handler.postDelayed(() -> runSequenceStep(steps, index + 1, output, handler, callback), delay);
    }

    private boolean isExecutionFailure(String response) {
        return response.startsWith("لم أجد") || response.startsWith("تعذر")
                || response.startsWith("لم أتمكن") || response.startsWith("فعّل")
                || response.startsWith("لم أستطع") || response.startsWith("وجدت")
                || response.startsWith("لا أستطيع");
    }

    public String execute(NovaCommand command) {
        if (command == null) return "لم أفهم الأمر.";
        try {
            switch (command.type) {
                case FLASHLIGHT_ON: return setFlashlight(true);
                case FLASHLIGHT_OFF: return setFlashlight(false);
                case TIME:
                    return "الساعة الآن " + new SimpleDateFormat("h:mm a", new Locale("ar")).format(new Date());
                case BATTERY: return batteryStatus();
                case VOLUME_UP: return adjustVolume(true);
                case VOLUME_DOWN: return adjustVolume(false);
                case SET_ALARM: return setAlarm(command.argument);
                case OPEN_APP: return openApp(command.argument);
                case BACK: return performNavigation(AccessibilityService.GLOBAL_ACTION_BACK, "رجعت للخلف.");
                case HOME: return performNavigation(AccessibilityService.GLOBAL_ACTION_HOME, "رجعت للشاشة الرئيسية.");
                case READ_NOTIFICATIONS: return CallNotificationListener.readLatestNotification();
                case CLICK_TEXT:
                    if (isSendLabel(command.argument)) {
                        return InCallAccessibilityService.prepareSendConfirmation();
                    }
                    return InCallAccessibilityService.clickVisibleText(command.argument);
                case CONFIRM_SEND:
                    return InCallAccessibilityService.confirmSendMessage();
                case TYPE_TEXT: return InCallAccessibilityService.typeIntoFocusedField(command.argument);
                case SCROLL_DOWN: return InCallAccessibilityService.scrollScreen(true);
                case SCROLL_UP: return InCallAccessibilityService.scrollScreen(false);
                case RECENTS: return performNavigation(AccessibilityService.GLOBAL_ACTION_RECENTS, "فتحت التطبيقات الأخيرة.");
                case OPEN_NOTIFICATIONS: return performNavigation(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "فتحت لوحة الإشعارات.");
                case QUICK_SETTINGS: return performNavigation(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "فتحت الإعدادات السريعة.");
                case STOP_LISTENING: return "أوقفت تنفيذ الأوامر. للاستئناف قل: نوفا استأنفي الاستماع.";
                case RESUME_LISTENING: return "عاد الاستماع للأوامر.";
                case MULTI_STEP: return "هذه المهمة تحتاج إلى تنفيذ متتابع عبر الخدمة الصوتية.";
                case WAKE_ONLY: return "نعم، أنا معك. تفضل.";
                default: return "لم أفهم الأمر. جرّب قول: شغّلي الكشاف، كم الساعة، أو اضبطي المنبه الساعة خمسة واثنتي عشرة.";
            }
        } catch (SecurityException e) {
            return "أحتاج إذنًا من إعدادات الهاتف لتنفيذ هذا الأمر.";
        } catch (Exception e) {
            return "تعذر تنفيذ الأمر. " + (e.getMessage() == null ? "" : e.getMessage());
        }
    }


    private String performNavigation(int action, String success) {
        if (!InCallAccessibilityService.isConnected())
            return "لتنفيذ هذا الأمر، فعّل خدمة إمكانية الوصول الخاصة بـ NOVA من إعدادات الهاتف.";
        return InCallAccessibilityService.performNovaGlobalAction(action) ? success : "لم أتمكن من تنفيذ التنقل الآن.";
    }

    private boolean isSendLabel(String label) {
        String n=NovaCommandParser.normalize(label);
        return n.contains("ارسال") || n.contains("send");
    }

    private String openApp(String spokenName) {
        String wanted = NovaCommandParser.normalize(spokenName);
        if (wanted.isEmpty()) return "ما اسم التطبيق الذي تريد فتحه؟";
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<ResolveInfo> apps = context.getPackageManager().queryIntentActivities(launcher, 0);
        String aliasPackage = appAliasPackage(wanted);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo info : apps) {
            if (info.activityInfo == null) continue;
            CharSequence labelCs = info.loadLabel(context.getPackageManager());
            String label = NovaCommandParser.normalize(labelCs == null ? "" : labelCs.toString());
            String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = aliasPackage != null && aliasPackage.equals(pkg) ? 120 : 0;
            if (label.equals(wanted)) score = Math.max(score, 100);
            else if ((label.contains(wanted) || wanted.contains(label)) && score < 70) score = 70;
            else if ((pkg.endsWith("." + wanted.replace(' ', '_')) || pkg.contains(wanted.replace(" ", ""))) && score < 45) score = 45;
            if (score > bestScore) { best = info; bestScore = score; }
        }
        if (best == null || bestScore < 45) return "لم أجد تطبيقًا باسم " + spokenName + ". جرّب الاسم الظاهر على الشاشة.";
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return "حاضر، أفتح " + best.loadLabel(context.getPackageManager()) + ".";
    }

    private String appAliasPackage(String name) {
        if (name.contains("واتساب") || name.contains("whatsapp")) return "com.whatsapp";
        if (name.contains("يوتيوب") || name.contains("youtube")) return "com.google.android.youtube";
        if (name.contains("تيليجرام") || name.contains("تلجرام") || name.contains("telegram")) return "org.telegram.messenger";
        if (name.contains("انستغرام") || name.contains("انستجرام") || name.contains("instagram")) return "com.instagram.android";
        if (name.contains("كروم") || name.contains("chrome")) return "com.android.chrome";
        if (name.contains("الكاميرا") || name.equals("كاميرا")) return "com.android.camera";
        if (name.contains("الاعدادات") || name.equals("اعدادات") || name.contains("settings")) return "com.android.settings";
        return null;
    }

    private String setFlashlight(boolean enabled) throws CameraAccessException {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return "اسمح لنوفا باستخدام الكاميرا لتشغيل الكشاف.";
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return "خدمة الكاميرا غير متاحة.";
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                manager.setTorchMode(id, enabled);
                return enabled ? "تم تشغيل الكشاف." : "تم إطفاء الكشاف.";
            }
        }
        return "هذا الهاتف لا يوفر كشافًا متاحًا.";
    }

    private String batteryStatus() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return "لم أتمكن من قراءة البطارية.";
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = scale > 0 ? Math.round(level * 100f / scale) : level;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        return "نسبة البطارية " + percent + " بالمئة" + (charging ? "، والهاتف قيد الشحن." : ".");
    }

    private String adjustVolume(boolean up) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return "التحكم بالصوت غير متاح.";
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        return up ? "تم رفع الصوت." : "تم خفض الصوت.";
    }

    private String setAlarm(String text) {
        String digits = toWesternDigits(text == null ? "" : text);
        Matcher matcher = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:٫.\\s]\\s*([0-5]\\d)(?!\\d)").matcher(digits);
        if (!matcher.find()) return "اذكر الوقت بالأرقام، مثل: اضبطي المنبه الساعة 5:12.";
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "منبه NOVA")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null)
            return "لم أجد تطبيق ساعة يدعم إنشاء المنبه.";
        context.startActivity(intent);
        return String.format(new Locale("ar"), "طلبت ضبط المنبه الساعة %d:%02d.", hour, minute);
    }

    private static String toWesternDigits(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '٠' && c <= '٩') c = (char) ('0' + c - '٠');
            else if (c >= '۰' && c <= '۹') c = (char) ('0' + c - '۰');
            out.append(c);
        }
        return out.toString();
    }
}
