package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import net.kdt.pojavlaunch.game.GameActivity;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import net.kdt.pojavlaunch.CallbackBridge;

import static net.kdt.pojavlaunch.customcontrols.buttons.BackgroundTint.DEFAULT_TINT_LIST;
import static net.kdt.pojavlaunch.customcontrols.buttons.BackgroundTint.TOGGLE_TINT_LIST;
import static net.kdt.pojavlaunch.game.platform.Platform.PLATFORM;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.WeakHashMap;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;
    private boolean mHasBitmap;

    protected boolean mIsToggled = false;

    // GLFW-style movement keycodes used by Pojav/Mojo control maps.
    private static final int MOVE_W = 87;
    private static final int MOVE_A = 65;
    private static final int MOVE_S = 83;
    private static final int MOVE_D = 68;
    private static final int INVALID_POINTER = -1;

    /**
     * Each movement button can be the origin of a drag gesture. Keep a per-layout reference count
     * so two fingers can share a direction without one finger releasing the key for the other.
     */
    private static final WeakHashMap<ControlLayout, HashMap<ControlButton, Integer>>
            DPAD_PRESS_COUNTS = new WeakHashMap<>();

    private final ArrayList<ControlButton> mDpadActiveButtons = new ArrayList<>(2);
    private int mDpadPointerId = INVALID_POINTER;

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    private void setupBitmapTint() {
        BackgroundTint.applyToggleTint(getContext());
        ColorStateList tintStateList = mProperties.isToggle ? TOGGLE_TINT_LIST : DEFAULT_TINT_LIST;
        setBackgroundTintList(tintStateList);
        setBackgroundTintMode(PorterDuff.Mode.SRC_ATOP);
    }

    private void setupNormalTint() {
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);
        setBackgroundTintList(null);
        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(BackgroundTint.BACKGROUND_TOGGLE_TINT_ALPHA);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(BackgroundTint.BACKGROUND_DEFAULT_TINT_ALPHA);
        }
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);

        mHasBitmap = Tools.isValidString(mProperties.bitmapTag);

        if(mHasBitmap) setupBitmapTint();
        else setupNormalTint();

        setText(properties.name);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Bitmap uses a tint list, so don't do any custom rendering
        if(mHasBitmap || !isActivated()) return;
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }

    @Override
    public boolean isActivated() {
        // Any possible side effects?
        return super.isActivated() || (mProperties.isToggle && mIsToggled);
    }

    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        ControlLayout parent = getControlLayoutParent();
        if(parent == null) return;
        parent.getLayout().mControlDataList.remove(getProperties());
        parent.removeView(this);
    }

    @Override
    public void handlePressed() {
        if(!getProperties().isToggle){
            sendKeyPresses(true);
        }
    }

    @Override
    public void handleReleased() {
        if(!triggerToggle()) {
            sendKeyPresses(false);
        }
    }

    private static boolean isMovementKey(int keycode) {
        return keycode == MOVE_W || keycode == MOVE_A || keycode == MOVE_S || keycode == MOVE_D;
    }

    /**
     * Only plain W/A/S/D buttons participate. A custom button containing another key or a
     * multi-key macro keeps the normal button behavior.
     */
    private int getMovementKey() {
        int movementKey = 0;
        for (int keycode : mProperties.keycodes) {
            if (keycode == 0) continue;
            if (!isMovementKey(keycode)) return 0;
            if (movementKey != 0 && movementKey != keycode) return 0;
            movementKey = keycode;
        }
        return movementKey;
    }

    private static float getButtonCenterX(ControlButton button) {
        return button.getX() + button.getWidth() / 2f;
    }

    private static float getButtonCenterY(ControlButton button) {
        return button.getY() + button.getHeight() / 2f;
    }

    private static float distanceSquared(ControlButton first, ControlButton second) {
        float dx = getButtonCenterX(first) - getButtonCenterX(second);
        float dy = getButtonCenterY(first) - getButtonCenterY(second);
        return dx * dx + dy * dy;
    }

    /**
     * Find the nearest W/A/S/D around the button where the finger started. This avoids mixing a
     * second custom movement cluster elsewhere on the screen into the current D-pad.
     */
    private HashMap<Integer, ControlButton> findMovementCluster() {
        HashMap<Integer, ControlButton> cluster = new HashMap<>();
        HashMap<Integer, Float> distances = new HashMap<>();

        float sourceSize = Math.max(getWidth(), getHeight());
        float maxDistance = Math.max(Tools.dpToPx(120), sourceSize * 5.5f);
        float maxDistanceSquared = maxDistance * maxDistance;

        for (ControlInterface control : mControlLayout.getButtonChildren()) {
            if (!(control instanceof ControlButton)) continue;
            ControlButton candidate = (ControlButton) control;
            if (candidate.getVisibility() != View.VISIBLE || candidate.mProperties.isToggle) continue;

            int movementKey = candidate.getMovementKey();
            if (movementKey == 0) continue;

            float distance = distanceSquared(this, candidate);
            if (candidate != this && distance > maxDistanceSquared) continue;

            Float previousDistance = distances.get(movementKey);
            if (previousDistance == null || distance < previousDistance) {
                distances.put(movementKey, distance);
                cluster.put(movementKey, candidate);
            }
        }
        return cluster;
    }

    private boolean shouldUseDpadGesture() {
        return !mControlLayout.getModifiable()
                && getMovementKey() != 0
                && findMovementCluster().size() >= 3;
    }

    private static void pressDpadReference(ControlLayout layout, ControlButton button) {
        synchronized (DPAD_PRESS_COUNTS) {
            HashMap<ControlButton, Integer> counts = DPAD_PRESS_COUNTS.get(layout);
            if (counts == null) {
                counts = new HashMap<>();
                DPAD_PRESS_COUNTS.put(layout, counts);
            }

            int count = counts.containsKey(button) ? counts.get(button) : 0;
            if (count == 0) button.handlePressed();
            counts.put(button, count + 1);
        }
    }

    private static void releaseDpadReference(ControlLayout layout, ControlButton button) {
        synchronized (DPAD_PRESS_COUNTS) {
            HashMap<ControlButton, Integer> counts = DPAD_PRESS_COUNTS.get(layout);
            if (counts == null) return;

            Integer count = counts.get(button);
            if (count == null) return;

            if (count <= 1) {
                counts.remove(button);
                button.handleReleased();
            } else {
                counts.put(button, count - 1);
            }

            if (counts.isEmpty()) DPAD_PRESS_COUNTS.remove(layout);
        }
    }

    private void updateDpadButtons(List<ControlButton> desiredButtons) {
        ArrayList<ControlButton> oldButtons = new ArrayList<>(mDpadActiveButtons);

        for (ControlButton oldButton : oldButtons) {
            if (!desiredButtons.contains(oldButton)) {
                releaseDpadReference(mControlLayout, oldButton);
                mDpadActiveButtons.remove(oldButton);
            }
        }

        for (ControlButton desiredButton : desiredButtons) {
            if (!mDpadActiveButtons.contains(desiredButton)) {
                pressDpadReference(mControlLayout, desiredButton);
                mDpadActiveButtons.add(desiredButton);
            }
        }
    }

    private void releaseDpadButtons() {
        if (mDpadActiveButtons.isEmpty()) {
            mDpadPointerId = INVALID_POINTER;
            return;
        }

        ArrayList<ControlButton> activeButtons = new ArrayList<>(mDpadActiveButtons);
        mDpadActiveButtons.clear();
        for (ControlButton button : activeButtons) {
            releaseDpadReference(mControlLayout, button);
        }
        mDpadPointerId = INVALID_POINTER;
    }

    private void updateDpadForPointer(MotionEvent event, int pointerIndex) {
        HashMap<Integer, ControlButton> cluster = findMovementCluster();
        if (cluster.size() < 3) {
            releaseDpadButtons();
            return;
        }

        float centerX = 0f;
        float centerY = 0f;
        float averageSize = 0f;
        int count = 0;
        for (ControlButton button : cluster.values()) {
            centerX += getButtonCenterX(button);
            centerY += getButtonCenterY(button);
            averageSize += Math.min(button.getWidth(), button.getHeight());
            count++;
        }
        centerX /= count;
        centerY /= count;
        averageSize /= count;

        // MotionEvent coordinates stay relative to the button that received ACTION_DOWN.
        float pointerX = getX() + event.getX(pointerIndex);
        float pointerY = getY() + event.getY(pointerIndex);
        float dx = pointerX - centerX;
        float dy = pointerY - centerY;
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);

        ArrayList<ControlButton> desired = new ArrayList<>(2);

        // Tiny center dead-zone prevents direction flicker while crossing the exact middle.
        float deadZone = Math.max(Tools.dpToPx(3), averageSize * 0.10f);
        if (absX <= deadZone && absY <= deadZone) {
            updateDpadButtons(desired);
            return;
        }

        // A strong axis means one key; around 45 degrees both axes stay held for diagonals.
        final float cardinalDominance = 1.60f;
        boolean horizontal = absX >= absY * cardinalDominance;
        boolean vertical = absY >= absX * cardinalDominance;

        if (!horizontal && !vertical) {
            horizontal = absX > deadZone;
            vertical = absY > deadZone;
        }

        if (vertical) {
            ControlButton verticalButton = cluster.get(dy < 0 ? MOVE_W : MOVE_S);
            if (verticalButton != null) desired.add(verticalButton);
        }
        if (horizontal) {
            ControlButton horizontalButton = cluster.get(dx < 0 ? MOVE_A : MOVE_D);
            if (horizontalButton != null && !desired.contains(horizontalButton)) {
                desired.add(horizontalButton);
            }
        }

        updateDpadButtons(desired);
    }

    private void handleDpadTouch(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                releaseDpadButtons();
                mDpadPointerId = event.getPointerId(0);
                updateDpadForPointer(event, 0);
                break;

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = event.findPointerIndex(mDpadPointerId);
                if (pointerIndex >= 0) updateDpadForPointer(event, pointerIndex);
                else releaseDpadButtons();
                break;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == mDpadPointerId) {
                    releaseDpadButtons();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseDpadButtons();
                break;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        ControlData properties = getProperties();
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                if(properties.passThruEnabled){
                    //Send the event to be taken as a mouse action
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                break;
        }

        if (mDpadPointerId != INVALID_POINTER || shouldUseDpadGesture()) {
            handleDpadTouch(event);
            return true;
        }

        if(getProperties().isSwipeable) {
            getControlLayoutParent().onTouch(this, event);
            return true;
        }

        switch (action){
            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                handlePressed();
                break;
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                handleReleased();
                break;
            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= KeyEvent.KEYCODE_UNKNOWN){
                CallbackBridge.setModifiers(keycode, isDown);
                int modifiers = CallbackBridge.getCurrentMods();
                PLATFORM.sendKeyEvent(keycode, isDown ? 1 : 0, modifiers);
            }else{
                Log.i("punjabilauncher", "sendSpecialKey("+keycode+","+isDown+")");
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) GameActivity.switchKeyboardState(false);
                break;

            case ControlData.SPECIALBTN_KEYBOARDPAN:
                if(isDown) GameActivity.switchKeyboardState(true);
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) GameActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(MotionEvent.BUTTON_PRIMARY, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(MotionEvent.BUTTON_TERTIARY, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(MotionEvent.BUTTON_SECONDARY, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseDpadButtons();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
