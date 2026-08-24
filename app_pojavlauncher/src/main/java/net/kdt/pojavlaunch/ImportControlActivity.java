package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.customcontrols.LayoutBitmaps;
import net.kdt.pojavlaunch.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import git.artdeell.mojo.R;

/**
 * An activity dedicated to importing control files.
 */
@SuppressWarnings("IOStreamConstructor")
public class ImportControlActivity extends Activity {
    private static final String LOG_TAG = "ImportControlActivity";
    private static final long MAX_IMPORT_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_FILE_NAME_LENGTH = 64;
    private static final int MAX_CONTROLS = 512;
    private static final int MAX_JOYSTICKS = 32;

    private Uri mUriData;
    private File mTempFile;
    private boolean mHasIntentChanged = true;
    private volatile boolean mIsFileVerified = false;
    private volatile boolean mImportCompleted = false;

    private EditText mEditText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(Tools.checkStorageInteractive(this)) {
            Tools.initStorageConstants(getApplicationContext());
        }else {
            // Return early, no initialization needed.
            return;
        }

        setContentView(R.layout.activity_import_control);
        mEditText = findViewById(R.id.editText_import_control_file_name);
    }

    /**
     * Override the previous loaded intent
     * @param intent the intent used to replace the old one.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        if(intent != null) setIntent(intent);
        mHasIntentChanged = true;
    }

    /**
     * Update all over again if the intent changed.
     */
    @Override
    protected void onPostResume() {
        super.onPostResume();
        if(!Tools.checkStorageInteractive(this)) {
            return;
        }
        if(!mHasIntentChanged) return;

        deleteTempFile();
        mIsFileVerified = false;
        mImportCompleted = false;
        getUriData();
        if(mUriData == null) {
            finishAndRemoveTask();
            return;
        }
        mEditText.setText(trimFileName(Tools.getFileName(this, mUriData)));
        mHasIntentChanged = false;

        // Import and verify off the UI thread. Untrusted input is copied through a strict size cap.
        new Thread(() -> {
            boolean valid = importControlFile() && verify(mTempFile);
            if(valid) {
                mIsFileVerified = true;
            } else {
                deleteTempFile();
                runOnUiThread(() -> {
                    Toast.makeText(
                            ImportControlActivity.this,
                            getText(R.string.import_control_invalid_file),
                            Toast.LENGTH_SHORT).show();
                    finishAndRemoveTask();
                });
            }
        }, "Kirazium-Control-Import").start();

        Tools.MAIN_HANDLER.postDelayed(() -> {
            if (isFinishing() || mEditText == null) return;
            InputMethodManager imm = (InputMethodManager) getApplicationContext().getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            mEditText.setSelection(mEditText.getText().length());
        }, 100);
    }

    /**
     * Start the import.
     * @param view the view which called the function
     */
    public void startImport(View view) {
        String fileName = trimFileName(mEditText.getText().toString());
        if(!isFileNameValid(fileName)){
            Toast.makeText(this, getText(R.string.import_control_invalid_name), Toast.LENGTH_SHORT).show();
            return;
        }
        if(!mIsFileVerified || mTempFile == null || !mTempFile.isFile()){
            Toast.makeText(this, getText(R.string.import_control_verifying_file), Toast.LENGTH_LONG).show();
            return;
        }

        File destination = new File(Tools.CTRLMAP_PATH, fileName + ".json");
        if (!isDestinationInsideControlDirectory(destination) || !mTempFile.renameTo(destination)) {
            Toast.makeText(this, getText(R.string.import_control_invalid_file), Toast.LENGTH_SHORT).show();
            return;
        }

        mImportCompleted = true;
        mTempFile = null;
        Toast.makeText(getApplicationContext(), getText(R.string.import_control_done), Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
    }

    /** Copy the shared file into a private temporary control-layout file with a strict byte cap. */
    private boolean importControlFile(){
        File controlDirectory = new File(Tools.CTRLMAP_PATH);
        try {
            FileUtils.ensureDirectory(controlDirectory);
            mTempFile = File.createTempFile("kirazium-control-import-", ".tmp", controlDirectory);

            try (InputStream input = getContentResolver().openInputStream(mUriData);
                 OutputStream output = new FileOutputStream(mTempFile)) {
                if (input == null) throw new IOException("Content provider returned no stream");

                byte[] buffer = new byte[16 * 1024];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_IMPORT_BYTES) {
                        throw new IOException("Control layout exceeds import size limit");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                if (total <= 0L) throw new IOException("Control layout is empty");
            }
            return true;
        } catch (Exception error) {
            Log.w(LOG_TAG, "Failed to safely copy shared control layout", error);
            deleteTempFile();
            return false;
        }
    }

    /**
     * Tell if the clean version of the filename is valid.
     */
    private static boolean isFileNameValid(String fileName){
        fileName = trimFileName(fileName);
        if(fileName.isEmpty() || fileName.length() > MAX_FILE_NAME_LENGTH) return false;
        if(".".equals(fileName) || "..".equals(fileName)) return false;
        for (int i = 0; i < fileName.length(); i++) {
            if (Character.isISOControl(fileName.charAt(i))) return false;
        }

        File destination = new File(Tools.CTRLMAP_PATH, fileName + ".json");
        return isDestinationInsideControlDirectory(destination) && !destination.exists();
    }

    /** Remove path separators and unsafe filesystem characters while preserving normal Unicode names. */
    private static String trimFileName(String fileName){
        if (fileName == null) return "";
        String clean = fileName.trim();
        if (clean.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            clean = clean.substring(0, clean.length() - 5);
        }
        clean = clean
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_')
                .replace('*', '_')
                .replace('?', '_')
                .replace('"', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace('|', '_')
                .trim();
        return clean.length() > MAX_FILE_NAME_LENGTH
                ? clean.substring(0, MAX_FILE_NAME_LENGTH).trim()
                : clean;
    }

    /** Tries to get a content:// Uri from the supported share intent forms. */
    @SuppressWarnings("deprecation")
    private void getUriData(){
        Intent intent = getIntent();
        Uri candidate = intent == null ? null : intent.getData();
        if (candidate == null && intent != null && intent.getClipData() != null
                && intent.getClipData().getItemCount() > 0) {
            candidate = intent.getClipData().getItemAt(0).getUri();
        }
        if (candidate == null && intent != null) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) candidate = (Uri) stream;
        }

        // Never let this exported activity read file://, http(s):// or custom-scheme data.
        mUriData = candidate != null && "content".equalsIgnoreCase(candidate.getScheme())
                ? candidate : null;
    }

    /**
     * Verify the parsed control structure and keep pathological list sizes out of the editor/game.
     */
    private static boolean verify(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > MAX_IMPORT_BYTES) {
            return false;
        }
        try {
            LayoutBitmaps.ControlsContainer layout = LayoutBitmaps.load(file);
            JSONObject layoutObject = new JSONObject(layout.mControlsJson);
            JSONArray controls = layoutObject.optJSONArray("mControlDataList");
            JSONArray joysticks = layoutObject.optJSONArray("mJoystickDataList");
            return layoutObject.has("version")
                    && controls != null
                    && controls.length() <= MAX_CONTROLS
                    && (joysticks == null || joysticks.length() <= MAX_JOYSTICKS);
        }catch (IOException | JSONException | RuntimeException error) {
            Log.w(LOG_TAG, "Failed to validate layout", error);
            return false;
        }
    }

    private static boolean isDestinationInsideControlDirectory(File destination) {
        try {
            File root = new File(Tools.CTRLMAP_PATH).getCanonicalFile();
            File target = destination.getCanonicalFile();
            return root.equals(target.getParentFile());
        } catch (IOException error) {
            return false;
        }
    }

    private void deleteTempFile() {
        File temp = mTempFile;
        mTempFile = null;
        if (temp != null && temp.isFile() && !temp.delete()) {
            Log.w(LOG_TAG, "Could not delete temporary control import " + temp);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!mImportCompleted) deleteTempFile();
    }
}
