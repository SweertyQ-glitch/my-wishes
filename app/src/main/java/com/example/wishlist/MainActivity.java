package com.example.wishlist;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Вся логика приложения находится здесь.
 * index.html + style.css отвечают только за внешний вид:
 * нажатия кнопок попадают в Java через мост Bridge (window.Android),
 * а Java меняет страницу через evaluateJavascript().
 */
public class MainActivity extends AppCompatActivity {

    // ---- ключи хранилища и идентификаторы экранов ----
    private static final String PREFS = "wishlist_prefs";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_DARK = "dark";
    private static final String KEY_BACKGROUND = "background";

    private static final String HOME = "homeScreen";
    private static final String CREATE = "createScreen";
    private static final String DETAILS = "detailsScreen";
    private static final String SETTINGS = "settingsScreen";

    private static final int PICK_PHOTO = 1;
    private static final int PICK_BACKGROUND = 2;

    private static final String BG_OVERLAY =
            "linear-gradient(rgba(20,20,24,.28), rgba(20,20,24,.38))";

    // ---- состояние приложения ----
    private WebView webView;
    private SharedPreferences prefs;
    private final List<Wish> wishes = new ArrayList<>();
    private String currentScreen = HOME;
    private String currentWishId;
    private String selectedPhoto = "";
    private int pickTarget;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideToastTask =
            () -> js(el("toast") + ".classList.remove('show');");

    // ---- выбор картинки из галереи ----
    private final ActivityResultLauncher<Intent> imagePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK
                        || result.getData() == null
                        || result.getData().getData() == null) {
                    return;
                }
                Uri uri = result.getData().getData();
                if (pickTarget == PICK_PHOTO) {
                    onPhotoPicked(uri);
                } else {
                    onBackgroundPicked(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                onPageReady();
            }
        });
        webView.loadUrl("file:///android_asset/index.html");

        // Кнопка «Назад»: сначала возвращаемся на главный экран, потом закрываем приложение
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!HOME.equals(currentScreen)) {
                    resetForm();
                    showScreen(HOME);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // =====================================================================
    //  МОСТ JavaScript -> Java (вызывается из onclick в index.html)
    // =====================================================================
    private class Bridge {
        @JavascriptInterface
        public void navigate(String screenId) {
            runOnUiThread(() -> {
                if (CREATE.equals(screenId)) {
                    openCreateForm(null);
                } else {
                    showScreen(screenId);
                }
            });
        }

        @JavascriptInterface
        public void openCreate() {
            runOnUiThread(() -> openCreateForm(null));
        }

        @JavascriptInterface
        public void cancelForm() {
            runOnUiThread(() -> {
                resetForm();
                showScreen(HOME);
            });
        }

        @JavascriptInterface
        public void openDetails(String id) {
            runOnUiThread(() -> MainActivity.this.openDetails(id));
        }

        @JavascriptInterface
        public void saveWish(String id, String name, String price, String link, String description) {
            runOnUiThread(() -> onSaveWish(id, name, price, link, description));
        }

        @JavascriptInterface
        public void editCurrent() {
            runOnUiThread(() -> {
                Wish wish = findWish(currentWishId);
                if (wish != null) openCreateForm(wish);
            });
        }

        @JavascriptInterface
        public void deleteCurrent() {
            runOnUiThread(MainActivity.this::confirmDelete);
        }

        @JavascriptInterface
        public void pickPhoto() {
            runOnUiThread(() -> launchImagePicker(PICK_PHOTO));
        }

        @JavascriptInterface
        public void pickBackground() {
            runOnUiThread(() -> launchImagePicker(PICK_BACKGROUND));
        }

        @JavascriptInterface
        public void resetBackground() {
            runOnUiThread(() -> {
                js("document.body.style.backgroundImage='';");
                prefs.edit().remove(KEY_BACKGROUND).apply();
                showToast("Стандартный фон восстановлен.");
            });
        }

        @JavascriptInterface
        public void setDarkTheme(boolean dark) {
            runOnUiThread(() -> applyTheme(dark));
        }
    }

    // =====================================================================
    //  ЗАПУСК И ВОССТАНОВЛЕНИЕ СОСТОЯНИЯ
    // =====================================================================
    private void onPageReady() {
        loadWishes();
        applyTheme(prefs.getBoolean(KEY_DARK, false));
        String background = prefs.getString(KEY_BACKGROUND, null);
        if (background != null) applyBackground(background);
        renderWishes();
        showScreen(HOME);
    }

    // =====================================================================
    //  ЭКРАНЫ
    // =====================================================================
    private void showScreen(String screenId) {
        currentScreen = screenId;
        String id = JSONObject.quote(screenId);
        js("document.querySelectorAll('.screen').forEach(function(s){"
                + "s.classList.toggle('active', s.id===" + id + ");});"
                + "document.querySelectorAll('.nav-button').forEach(function(b){"
                + "b.classList.toggle('active', b.dataset.screen===" + id + ");});"
                + el("wishCount") + ".style.visibility="
                + JSONObject.quote(HOME.equals(screenId) ? "visible" : "hidden") + ";"
                + "window.scrollTo({top:0,behavior:'smooth'});");
    }

    private void openCreateForm(Wish wish) {
        resetForm();
        if (wish != null) {
            setText("formTitle", "Изменить желание");
            setText("saveWishButton", "Сохранить изменения");
            setValue("wishId", wish.id);
            setValue("nameInput", wish.name);
            setValue("priceInput", wish.price);
            setValue("linkInput", wish.link);
            setValue("descriptionInput", wish.description);
            selectedPhoto = wish.photo;
            if (!selectedPhoto.isEmpty()) showPhotoPreview(selectedPhoto);
        }
        showScreen(CREATE);
        js(el("nameInput") + ".focus({preventScroll:true});");
    }

    private void resetForm() {
        js(el("wishForm") + ".reset();");
        setValue("wishId", "");
        selectedPhoto = "";
        js(el("photoPreview") + ".removeAttribute('src');"
                + el("photoPreview") + ".hidden=true;"
                + el("photoPlaceholder") + ".hidden=false;");
        setText("formTitle", "Новое желание");
        setText("saveWishButton", "Сохранить желание");
    }

    private void showPhotoPreview(String dataUri) {
        js(el("photoPreview") + ".src=" + JSONObject.quote(dataUri) + ";"
                + el("photoPreview") + ".hidden=false;"
                + el("photoPlaceholder") + ".hidden=true;");
    }

    private void openDetails(String id) {
        Wish wish = findWish(id);
        if (wish == null) return;
        currentWishId = id;

        setText("detailsName", wish.name);
        setText("detailsPrice", formatPrice(wish.price));
        setText("detailsDescription", wish.description);
        setHidden("detailsDescription", wish.description.isEmpty());

        if (!wish.photo.isEmpty()) {
            js(el("detailsPhoto") + ".src=" + JSONObject.quote(wish.photo) + ";"
                    + el("detailsPhoto") + ".alt=" + JSONObject.quote(wish.name) + ";");
            setHidden("detailsPhoto", false);
            setHidden("detailsPhotoFallback", true);
        } else {
            setHidden("detailsPhoto", true);
            setHidden("detailsPhotoFallback", false);
        }

        String safeLink = normalizeUrl(wish.link);
        if (!safeLink.isEmpty()) {
            js(el("detailsLink") + ".href=" + JSONObject.quote(safeLink) + ";");
            setHidden("detailsLink", false);
        } else {
            js(el("detailsLink") + ".removeAttribute('href');");
            setHidden("detailsLink", true);
        }
        showScreen(DETAILS);
    }

    // =====================================================================
    //  РЕНДЕР СПИСКА ЖЕЛАНИЙ
    // =====================================================================
    private void renderWishes() {
        StringBuilder html = new StringBuilder();
        for (Wish wish : wishes) {
            html.append("<article class=\"wish-card\" tabindex=\"0\" role=\"button\" aria-label=\"")
                    .append(escapeHtml("Открыть желание: " + wish.name))
                    .append("\" onclick=\"Android.openDetails('").append(wish.id).append("')\">");
            if (!wish.photo.isEmpty()) {
                html.append("<img class=\"wish-image\" src=\"").append(wish.photo)
                        .append("\" alt=\"").append(escapeHtml(wish.name)).append("\">");
            } else {
                html.append("<div class=\"wish-image-fallback\">✧</div>");
            }
            html.append("<div class=\"wish-info\"><h3>").append(escapeHtml(wish.name))
                    .append("</h3><p>").append(escapeHtml(formatPrice(wish.price)))
                    .append("</p></div></article>");
        }
        js(el("wishlistGrid") + ".innerHTML=" + JSONObject.quote(html.toString()) + ";");

        int n = wishes.size();
        setText("wishCount", n + " " + pluralize(n, "желание", "желания", "желаний"));
        js(el("emptyState") + ".classList.toggle('visible'," + (n == 0) + ");");
    }

    // =====================================================================
    //  СОХРАНЕНИЕ / УДАЛЕНИЕ
    // =====================================================================
    private void onSaveWish(String id, String rawName, String rawPrice, String rawLink, String rawDescription) {
        String name = rawName.trim();
        if (name.isEmpty()) {
            js(el("nameInput") + ".focus();");
            showToast("Введи название желания.");
            return;
        }

        String link = rawLink.trim();
        if (!link.isEmpty()) {
            link = normalizeUrl(link);
            if (link.isEmpty()) {
                js(el("linkInput") + ".focus();");
                showToast("Введи корректную ссылку, начинающуюся с https://");
                return;
            }
        }

        Wish existing = id.isEmpty() ? null : findWish(id);
        List<Wish> backup = new ArrayList<>(wishes);

        if (existing != null) {
            existing.name = name;
            existing.price = rawPrice.trim();
            existing.link = link;
            existing.description = rawDescription.trim();
            existing.photo = selectedPhoto;
        } else {
            wishes.add(0, new Wish(UUID.randomUUID().toString(), name, rawPrice.trim(), link,
                    rawDescription.trim(), selectedPhoto, System.currentTimeMillis()));
        }

        if (!saveWishes()) {
            wishes.clear();
            wishes.addAll(backup);
            return;
        }

        renderWishes();
        resetForm();
        showScreen(HOME);
        showToast(existing != null ? "Желание обновлено." : "Желание добавлено.");
    }

    private void confirmDelete() {
        Wish wish = findWish(currentWishId);
        if (wish == null) return;
        new AlertDialog.Builder(this)
                .setMessage("Удалить «" + wish.name + "»?")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    wishes.remove(wish);
                    saveWishes();
                    renderWishes();
                    showScreen(HOME);
                    showToast("Желание удалено.");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private Wish findWish(String id) {
        if (id == null) return null;
        for (Wish w : wishes) {
            if (w.id.equals(id)) return w;
        }
        return null;
    }

    // =====================================================================
    //  ХРАНИЛИЩЕ (SharedPreferences + JSON)
    // =====================================================================
    private void loadWishes() {
        wishes.clear();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                wishes.add(Wish.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
            wishes.clear();
        }
    }

    private boolean saveWishes() {
        try {
            JSONArray array = new JSONArray();
            for (Wish w : wishes) array.put(w.toJson());
            prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
            return true;
        } catch (Exception e) {
            showToast("Не удалось сохранить данные.");
            return false;
        }
    }

    // =====================================================================
    //  ТЕМА И ФОН
    // =====================================================================
    private void applyTheme(boolean dark) {
        js("document.body.classList.toggle('dark'," + dark + ");"
                + el("themeToggle") + ".checked=" + dark + ";");
        prefs.edit().putBoolean(KEY_DARK, dark).apply();
    }

    private void applyBackground(String dataUri) {
        String css = BG_OVERLAY + ", url(\"" + dataUri + "\")";
        js("document.body.style.backgroundImage=" + JSONObject.quote(css) + ";"
                + "document.body.style.backgroundSize='cover';"
                + "document.body.style.backgroundPosition='center';");
    }

    // =====================================================================
    //  ВЫБОР И СЖАТИЕ ИЗОБРАЖЕНИЙ
    // =====================================================================
    private void launchImagePicker(int target) {
        pickTarget = target;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        imagePicker.launch(intent);
    }

    private void onPhotoPicked(Uri uri) {
        String data = encodeImage(uri, 800);
        if (data == null) {
            showToast("Не удалось открыть изображение.");
            return;
        }
        selectedPhoto = data;
        showPhotoPreview(data);
    }

    private void onBackgroundPicked(Uri uri) {
        String data = encodeImage(uri, 1280);
        if (data == null) {
            showToast("Не удалось открыть изображение.");
            return;
        }
        applyBackground(data);
        prefs.edit().putString(KEY_BACKGROUND, data).apply();
        showToast("Фон обновлён.");
    }

    /** Уменьшает картинку и превращает её в data:URI, чтобы показать в WebView. */
    private String encodeImage(Uri uri, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap == null) return null;

            float scale = Math.min(1f, (float) maxSide / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f) {
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(bitmap.getWidth() * scale),
                        Math.round(bitmap.getHeight() * scale), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    //  ФОРМАТИРОВАНИЕ И ПРОВЕРКИ
    // =====================================================================
    private String formatPrice(String value) {
        if (value == null || value.trim().isEmpty()) return "Цена не указана";
        try {
            double number = Double.parseDouble(value.trim());
            NumberFormat format = NumberFormat.getNumberInstance(new Locale("ru", "RU"));
            format.setMaximumFractionDigits(2);
            return format.format(number) + " ₽";
        } catch (NumberFormatException e) {
            return "Цена не указана";
        }
    }

    private String pluralize(int number, String one, String few, String many) {
        int n = Math.abs(number) % 100;
        int last = n % 10;
        if (n > 10 && n < 20) return many;
        if (last > 1 && last < 5) return few;
        if (last == 1) return one;
        return many;
    }

    /** Возвращает ссылку, только если это корректный http/https URL, иначе пустую строку. */
    private String normalizeUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                return uri.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // =====================================================================
    //  ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ РАБОТЫ СО СТРАНИЦЕЙ
    // =====================================================================
    private void showToast(String message) {
        setText("toast", message);
        js(el("toast") + ".classList.add('show');");
        handler.removeCallbacks(hideToastTask);
        handler.postDelayed(hideToastTask, 2600);
    }

    private void js(String script) {
        webView.evaluateJavascript(script, null);
    }

    private String el(String id) {
        return "document.getElementById(" + JSONObject.quote(id) + ")";
    }

    private void setText(String id, String text) {
        js(el(id) + ".textContent=" + JSONObject.quote(text) + ";");
    }

    private void setValue(String id, String value) {
        js(el(id) + ".value=" + JSONObject.quote(value == null ? "" : value) + ";");
    }

    private void setHidden(String id, boolean hidden) {
        js(el(id) + ".hidden=" + hidden + ";");
    }
}
