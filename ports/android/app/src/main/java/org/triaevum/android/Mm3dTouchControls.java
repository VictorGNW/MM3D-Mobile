package org.triaevum.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.RelativeLayout;

final class Mm3dTouchControls extends View {
    // Standard/Extra HID bits from ThreeDsRecomp::Input::Button.
    private static final int A = 1, B = 2, SELECT = 4, START = 8;
    private static final int RIGHT = 16, LEFT = 32, UP = 64, DOWN = 128;
    private static final int R = 256, L = 512, X = 1024, Y = 2048;
    private static final int ZL = 1 << 14, ZR = 1 << 15;
    // Host commands use the remaining bits of the touch bridge.
    private static final int ITEMS = 1 << 16, MAP = 1 << 17, NOTEBOOK = 1 << 18;
    private static final int MENU = 1 << 19;
    private static final int SKIP_INTRO = 1 << 20;

    private static final int FILL = 0x99505274;
    private static final int PANEL = 0xB5161729;
    private static final int BORDER = 0xAFC3C1D2;
    private static final int LABEL = 0xFFE0DFEA;
    private static final int GOLD = 0xFFD4A143;
    private static final int PRESSED = 0xDB666B8A;
    private static final int DARK = 0xFF241807;
    private static final int[] ARROW_DIRECTIONS = {UP, RIGHT, DOWN, LEFT};

    private enum Shape { PILL, SHORTCUT, ROUND, DPAD, STICK }

    private static final class Control {
        final int button;
        final String label;
        final Shape shape;
        final RectF bounds = new RectF();
        int opacity = 100, size = 100;
        float positionX = -1, positionY = -1;
        View target;
        int pointer = -1;
        float x, y;
        int direction;

        Control(int button, String label, Shape shape) {
            this.button = button;
            this.label = label;
            this.shape = shape;
        }

        boolean hits(float x, float y) {
            if (!bounds.contains(x, y)) return false;
            float dx = Math.abs(x - bounds.centerX());
            float dy = Math.abs(y - bounds.centerY());
            return switch (shape) {
                case ROUND, STICK -> Math.hypot(dx, dy) <= bounds.width() / 2;
                case DPAD -> Math.min(dx, dy) <= bounds.width() * 0.176f;
                default -> true;
            };
        }

        void place(float x, float y, float w, float h, float scale) {
            bounds.set((x - w / 2) * scale, (y - h / 2) * scale,
                       (x + w / 2) * scale, (y + h / 2) * scale);
        }
    }

    private final Control l = new Control(L, "L", Shape.PILL);
    private final Control zl = new Control(ZL, "ZL", Shape.PILL);
    private final Control r = new Control(R, "R", Shape.PILL);
    private final Control zr = new Control(ZR, "ZR", Shape.PILL);
    private final Control dpad = new Control(0, "", Shape.DPAD);
    private final Control circlePad = new Control(0, "", Shape.STICK);
    private final Control cStick = new Control(0, "C", Shape.STICK);
    private final Control x = new Control(X, "X", Shape.ROUND);
    private final Control y = new Control(Y, "Y", Shape.ROUND);
    private final Control a = new Control(A, "A", Shape.ROUND);
    private final Control b = new Control(B, "B", Shape.ROUND);
    private final Control items = new Control(ITEMS, "Itens", Shape.SHORTCUT);
    private final Control map = new Control(MAP, "Mapa", Shape.SHORTCUT);
    private final Control notebook = new Control(NOTEBOOK, "Caderno", Shape.SHORTCUT);
    private final Control select = new Control(SELECT, "Select", Shape.PILL);
    private final Control start = new Control(START, "Start", Shape.PILL);
    private final Control menu = new Control(MENU, "", Shape.ROUND);
    private final Control[] controls = {
        l, zl, r, zr, dpad, circlePad, cStick, x, y, a, b, items, map, notebook, select, start, menu
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cross = new Path();
    private final RectF safeArea = new RectF();
    private final SharedPreferences preferences;
    private Mm3dTouchEditor editor;
    private Button editButton;
    private Control selected;
    private float scale;
    private int editingPointer = -1;
    private float dragOffsetX, dragOffsetY;
    private boolean settingsOpen;
    private boolean titleScreen;

    Mm3dTouchControls(Context context) {
        super(context);
        preferences = context.getSharedPreferences("mm3d_touch_layout", Context.MODE_PRIVATE);
        restoreLayout();
        setWillNotDraw(false);
        setVisibility(INVISIBLE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void attachTo(RelativeLayout parent) {
        parent.setMotionEventSplittingEnabled(true);
        parent.addView(this, new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        for (Control control : controls) {
            // Sibling targets let Android split fingers between individual
            // controls and the SDL surface, even in the same gesture.
            control.target = new View(getContext());
            control.target.setVisibility(INVISIBLE);
            control.target.setOnTouchListener((target, event) ->
                control == menu ? handleMenuTouch(event) : handleTouch(control, event));
            parent.addView(control.target, new RelativeLayout.LayoutParams(0, 0));
        }
        menu.target.setContentDescription("Configurações. Pressione e segure para editar controles");
        editButton = new Button(getContext());
        editButton.setText("Editar controles");
        editButton.setAllCaps(false);
        editButton.setVisibility(GONE);
        editButton.setOnClickListener(view -> openEditor());
        parent.addView(editButton);

        String[] names = new String[controls.length];
        for (int i = 0; i < controls.length; i++) names[i] = controlName(controls[i]);
        editor = new Mm3dTouchEditor(getContext(), () -> closeEditor(true),
            () -> closeEditor(false), this::resetLayout, names, index -> {
                selected = controls[index];
                updateSelection();
            }, this::setOpacity, this::setSize);
        editor.setVisibility(GONE);
        parent.addView(editor);
    }

    private static native void nativeSetInput(int buttons, float leftX, float leftY,
                                              float rightX, float rightY, boolean reset);

    void setMode(boolean enabled, boolean settingsOpen, boolean titleScreen) {
        if (this.settingsOpen != settingsOpen || this.titleScreen != titleScreen
                || (getVisibility() == VISIBLE) != enabled) {
            releaseInput();
            this.settingsOpen = settingsOpen;
            this.titleScreen = titleScreen;
            if (!enabled) closeEditor(false);
            start.target.setContentDescription(titleScreen ? "Pular abertura" : "Start");
            setVisibility(enabled ? VISIBLE : INVISIBLE);
            updateTargetVisibility();
            invalidate();
        }
    }

    void releaseInput() {
        removeCallbacks(longPressMenu);
        editingPointer = -1;
        for (Control control : controls) {
            control.pointer = -1;
            control.x = control.y = 0;
            control.direction = 0;
        }
        nativeSetInput(0, 0, 0, 0, 0, true);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        releaseInput();
        layoutControls(getRootWindowInsets());
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        releaseInput();
        layoutControls(insets);
        return super.onApplyWindowInsets(insets);
    }

    private void layoutControls(WindowInsets insets) {
        Insets safe = Insets.NONE;
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            } else {
                safe = insets.getSystemWindowInsets();
            }
        }
        int left = safe.left;
        int right = getWidth() - safe.right;
        int top = safe.top;
        int bottom = getHeight() - safe.bottom;
        safeArea.set(left, top, right, bottom);
        scale = Math.min(safeArea.width() / 1300f, safeArea.height() / 600f);
        if (scale <= 0) {
            updateTargetVisibility();
            return;
        }

        float extraHeight = bottom - top - 600 * scale;
        l.place(85, 56, 124, 65, scale);
        zl.place(85, 133, 124, 65, scale);
        dpad.place(117, 278, 166, 166, scale);
        circlePad.place(117, 476, 170, 170, scale);
        for (Control control : new Control[]{l, zl, dpad, circlePad}) {
            control.bounds.offset(left, top);
        }

        dpad.bounds.offset(0, extraHeight / 2);
        circlePad.bounds.offset(0, extraHeight);

        r.place(-83, 56, 124, 65, scale);
        zr.place(-83, 133, 124, 65, scale);
        cStick.place(-162, 251, 130, 130, scale);
        x.place(-162, 374, 84, 84, scale);
        y.place(-236, 448, 84, 84, scale);
        a.place(-88, 448, 86, 86, scale);
        b.place(-162, 522, 84, 84, scale);
        for (Control control : new Control[]{r, zr, cStick, x, y, a, b}) {
            control.bounds.offset(right, top);
        }

        cStick.bounds.offset(0, extraHeight / 2);
        for (Control control : new Control[]{x, y, a, b}) {
            control.bounds.offset(0, extraHeight);
        }

        items.place(-306, 53, 134, 65, scale);
        map.place(-161, 53, 134, 65, scale);
        notebook.place(-16, 53, 134, 65, scale);
        select.place(126, 53, 96, 65, scale);
        start.place(233, 53, 96, 65, scale);
        menu.place(341, 53, 66, 66, scale);
        for (Control control : new Control[]{items, map, notebook, select, start, menu}) {
            control.bounds.offset((left + right) / 2f, top);
        }
        for (Control control : controls) {
            float centerX = control.positionX < 0 ? control.bounds.centerX()
                : left + control.positionX * safeArea.width();
            float centerY = control.positionY < 0 ? control.bounds.centerY()
                : top + control.positionY * safeArea.height();
            float halfWidth = control.bounds.width() * control.size / 200f;
            float halfHeight = control.bounds.height() * control.size / 200f;
            centerX = clamp(centerX, left + halfWidth, right - halfWidth);
            centerY = clamp(centerY, top + halfHeight, bottom - halfHeight);
            control.bounds.set(centerX - halfWidth, centerY - halfHeight,
                               centerX + halfWidth, centerY + halfHeight);
        }
        // A size change can happen midway through the parent's layout pass.
        // Apply the new sibling bounds on the following UI traversal.
        removeCallbacks(placeTouchTargets);
        post(placeTouchTargets);
        invalidate();
    }

    private final Runnable placeTouchTargets = () -> {
        for (Control control : controls) {
            int left = (int) Math.floor(control.bounds.left);
            int top = (int) Math.floor(control.bounds.top);
            int width = (int) Math.ceil(control.bounds.right) - left;
            int height = (int) Math.ceil(control.bounds.bottom) - top;
            RelativeLayout.LayoutParams params =
                (RelativeLayout.LayoutParams) control.target.getLayoutParams();
            if (params.leftMargin != left || params.topMargin != top ||
                params.width != width || params.height != height) {
                params.leftMargin = left;
                params.topMargin = top;
                params.width = width;
                params.height = height;
                control.target.setLayoutParams(params);
            }
        }
        positionEditor();
        updateTargetVisibility();
    };

    private void updateTargetVisibility() {
        for (Control control : controls) {
            boolean usable = getVisibility() == VISIBLE && scale > 0 && selected == null &&
                             (!settingsOpen || control == menu);
            control.target.setVisibility(usable ? VISIBLE : INVISIBLE);
        }
        editButton.setVisibility(getVisibility() == VISIBLE && scale > 0 && settingsOpen &&
                                 selected == null ? VISIBLE : GONE);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (scale <= 0) return;
        for (Control control : controls) {
            if (settingsOpen && selected == null && control != menu) continue;
            canvas.save();
            if (control.opacity != 100) {
                RectF b = control.bounds;
                canvas.saveLayerAlpha(b.left - 4 * scale, b.top - 4 * scale,
                    b.right + 4 * scale, b.bottom + 4 * scale,
                    Math.round(control.opacity * 2.55f));
            }
            canvas.translate(control.bounds.centerX(), control.bounds.centerY());
            float drawingScale = scale * control.size / 100f;
            canvas.scale(drawingScale, drawingScale);
            drawControl(canvas, control, drawingScale);
            if (control.opacity != 100) canvas.restore();
            canvas.restore();
        }
        if (selected != null) {
            stroke(GOLD, 2 * scale);
            canvas.drawRoundRect(selected.bounds, 8 * scale, 8 * scale, paint);
        }
    }

    private void drawControl(Canvas canvas, Control control, float drawingScale) {
        boolean held = control.pointer != -1;
        float rx = control.bounds.width() / (2 * drawingScale);
        float ry = control.bounds.height() / (2 * drawingScale);
        int fill = control == a ? (held ? 0xFFF7C565 : GOLD) : (held ? PRESSED : PANEL);
        if (control == dpad) {
            drawDpad(canvas, rx);
        } else if (control.shape == Shape.STICK) {
            drawCircle(canvas, 0, 0, rx, PANEL, BORDER);
            if (control == circlePad) drawArrows(canvas, rx * 0.75f, 0);
            float knob = rx * (control == cStick ? 0.5f : 0.47f);
            float knobX = control.x * (rx - knob);
            float knobY = control.y * (rx - knob);
            drawCircle(canvas, knobX, knobY, knob, held ? PRESSED : FILL, BORDER);
            drawLabel(canvas, control.label, knobX, knobY, 25, LABEL);
        } else {
            float radius = control.shape == Shape.SHORTCUT ? 17 : ry;
            paint.setColor(fill);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(-rx, -ry, rx, ry, radius, radius, paint);
            if (control != a) {
                stroke(BORDER, 2.5f);
                canvas.drawRoundRect(-rx, -ry, rx, ry, radius, radius, paint);
            }
            if (control == menu) {
                stroke(LABEL, 2.5f);
                canvas.drawLine(-9, -6, 9, -6, paint);
                canvas.drawLine(-9, 0, 9, 0, paint);
                canvas.drawLine(-9, 6, 9, 6, paint);
            } else if (control.shape == Shape.SHORTCUT) {
                drawShortcutIcon(canvas, control.button);
                drawLabel(canvas, control.label, 16, 0, 21, LABEL);
            } else {
                float size = control.shape == Shape.ROUND ? 34 : control == select || control == start ? 21 : 25;
                String label = control == start && titleScreen ? "Pular" : control.label;
                drawLabel(canvas, label, 0, 0, size, control == a ? DARK : LABEL);
            }
        }
    }

    private void drawDpad(Canvas canvas, float r) {
        float arm = r * 0.352f;
        cross.rewind();
        cross.moveTo(-arm, -r);
        cross.lineTo(arm, -r);
        cross.lineTo(arm, -arm);
        cross.lineTo(r, -arm);
        cross.lineTo(r, arm);
        cross.lineTo(arm, arm);
        cross.lineTo(arm, r);
        cross.lineTo(-arm, r);
        cross.lineTo(-arm, arm);
        cross.lineTo(-r, arm);
        cross.lineTo(-r, -arm);
        cross.lineTo(-arm, -arm);
        cross.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PANEL);
        canvas.drawPath(cross, paint);
        stroke(BORDER, 3);
        canvas.drawPath(cross, paint);
        drawArrows(canvas, r * 0.70f, dpad.direction);
    }

    private void drawArrows(Canvas canvas, float distance, int active) {
        canvas.save();
        for (int direction : ARROW_DIRECTIONS) {
            path.rewind();
            path.moveTo(0, -distance - 9);
            path.lineTo(-12, -distance + 6);
            path.lineTo(12, -distance + 6);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((active & direction) != 0 ? GOLD : BORDER);
            canvas.drawPath(path, paint);
            canvas.rotate(90);
        }
        canvas.restore();
    }

    private void drawShortcutIcon(Canvas canvas, int button) {
        canvas.save();
        canvas.translate(button == NOTEBOOK ? -47 : -32, 0);
        stroke(LABEL, 2);
        path.rewind();
        if (button == ITEMS) {
            canvas.drawArc(-4, -9, 4, -2, 180, 180, false, paint);
            path.moveTo(-7, -5);
            path.lineTo(7, -5);
            path.lineTo(8, 9);
            path.lineTo(-8, 9);
        } else if (button == MAP) {
            path.moveTo(-11, -7);
            path.lineTo(-4, -10);
            path.lineTo(4, -7);
            path.lineTo(11, -10);
            path.lineTo(11, 6);
            path.lineTo(4, 9);
            path.lineTo(-4, 6);
            path.lineTo(-11, 9);
            canvas.drawLine(-4, -10, -4, 6, paint);
            canvas.drawLine(4, -7, 4, 9, paint);
        } else {
            path.moveTo(-9, 11);
            path.lineTo(-9, -9);
            path.lineTo(9, -9);
            path.lineTo(9, 8);
            path.lineTo(-6, 8);
        }
        path.close();
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void drawCircle(Canvas canvas, float x, float y, float radius, int fill, int border) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawCircle(x, y, radius, paint);
        stroke(border, 3);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
    }

    private void drawLabel(Canvas canvas, String label, float x, float y, float size, int color) {
        if (label.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(color);
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    private final Runnable longPressMenu = this::openEditor;

    private boolean handleMenuTouch(MotionEvent event) {
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE ||
                    !menu.hits(menu.target.getLeft() + event.getX(index),
                               menu.target.getTop() + event.getY(index))) return false;
                menu.pointer = id;
                postDelayed(longPressMenu, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                int pointer = event.findPointerIndex(menu.pointer);
                if (pointer >= 0 && !menu.hits(menu.target.getLeft() + event.getX(pointer),
                                               menu.target.getTop() + event.getY(pointer))) {
                    removeCallbacks(longPressMenu);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (menu.pointer == id) {
                    removeCallbacks(longPressMenu);
                    menu.pointer = -1;
                    if (menu.hits(menu.target.getLeft() + event.getX(index),
                                  menu.target.getTop() + event.getY(index))) {
                        toggleSettings();
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressMenu);
                menu.pointer = -1;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void toggleSettings() {
        publishInput(MENU);
        publishInput(0);
    }

    private void openEditor() {
        if (selected != null || scale <= 0 || getVisibility() != VISIBLE) return;
        releaseInput();
        selected = a;
        editor.setVisibility(VISIBLE);
        updateSelection();
        updateTargetVisibility();
        if (settingsOpen) toggleSettings();
    }

    boolean closeEditor(boolean save) {
        if (selected == null) return false;
        if (save) {
            SharedPreferences.Editor changes = preferences.edit();
            for (Control control : controls) {
                String key = preferenceKey(control);
                changes.putInt(key + ".opacity", control.opacity)
                       .putInt(key + ".size", control.size)
                       .putFloat(key + ".x", control.positionX)
                       .putFloat(key + ".y", control.positionY);
            }
            changes.apply();
        } else {
            restoreLayout();
        }
        selected = null;
        editor.setVisibility(GONE);
        releaseInput();
        layoutControls(getRootWindowInsets());
        updateTargetVisibility();
        return true;
    }

    private String preferenceKey(Control control) {
        if (control == dpad) return "dpad";
        if (control == circlePad) return "circle_pad";
        if (control == cStick) return "c_stick";
        return Integer.toString(control.button);
    }

    private void restoreLayout() {
        for (Control control : controls) {
            String key = preferenceKey(control);
            control.opacity = (int) clamp(preferences.getInt(key + ".opacity", 100),
                                         Mm3dTouchEditor.MIN_OPACITY, 100);
            control.size = (int) clamp(preferences.getInt(key + ".size", 100),
                                      Mm3dTouchEditor.MIN_SIZE, Mm3dTouchEditor.MAX_SIZE);
            control.positionX = clamp(preferences.getFloat(key + ".x", -1), -1, 1);
            control.positionY = clamp(preferences.getFloat(key + ".y", -1), -1, 1);
        }
    }

    private void resetLayout() {
        for (Control control : controls) {
            control.opacity = control.size = 100;
            control.positionX = control.positionY = -1;
        }
        layoutControls(getRootWindowInsets());
        updateSelection();
    }

    private String controlName(Control control) {
        if (control == circlePad) return "Analógico esquerdo";
        if (control == dpad) return "D-pad";
        if (control == menu) return "Menu";
        if (control == cStick) return "Analógico C";
        return control.label;
    }

    private void updateSelection() {
        editor.showSelection(controlName(selected), selected.opacity, selected.size);
        positionEditor();
        invalidate();
    }

    private void setOpacity(int percent) {
        selected.opacity = percent;
        invalidate();
    }

    private void setSize(int percent) {
        selected.size = percent;
        layoutControls(getRootWindowInsets());
    }

    private void positionEditor() {
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        RelativeLayout.LayoutParams entry = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        entry.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        entry.topMargin = (int) safeArea.top + gap;
        entry.rightMargin = (int) (getWidth() - safeArea.right) + gap;
        editButton.setLayoutParams(entry);
        if (selected == null) return;

        int maxWidth = Math.round(430 * getResources().getDisplayMetrics().density);
        int width = Math.max(0, Math.min(maxWidth, (int) safeArea.width() - 2 * gap));
        RelativeLayout.LayoutParams panel = new RelativeLayout.LayoutParams(
            width, RelativeLayout.LayoutParams.WRAP_CONTENT);
        panel.leftMargin = (int) safeArea.centerX() - width / 2;
        if (selected.bounds.centerY() >= safeArea.centerY()) {
            panel.topMargin = (int) safeArea.top + gap;
        } else {
            panel.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            panel.bottomMargin = (int) (getHeight() - safeArea.bottom) + gap;
        }
        editor.setLayoutParams(panel);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (selected == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float x = event.getX(), y = event.getY();
                Control hit = selected.hits(x, y) ? selected : null;
                for (int i = controls.length - 1; hit == null && i >= 0; --i) {
                    if (controls[i].hits(x, y)) hit = controls[i];
                }
                if (hit != null) {
                    selected = hit;
                    editingPointer = event.getPointerId(0);
                    dragOffsetX = hit.bounds.centerX() - x;
                    dragOffsetY = hit.bounds.centerY() - y;
                    updateSelection();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                int pointer = event.findPointerIndex(editingPointer);
                if (pointer >= 0) moveSelection(event.getX(pointer), event.getY(pointer));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                int index = event.getActionIndex();
                if (event.getPointerId(index) == editingPointer) {
                    moveSelection(event.getX(index), event.getY(index));
                    editingPointer = -1;
                    positionEditor();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                editingPointer = -1;
                positionEditor();
                break;
            default:
                break;
        }
        return true;
    }

    private void moveSelection(float x, float y) {
        RectF bounds = selected.bounds;
        float halfWidth = bounds.width() / 2;
        float halfHeight = bounds.height() / 2;
        float cx = clamp(x + dragOffsetX, safeArea.left + halfWidth, safeArea.right - halfWidth);
        float cy = clamp(y + dragOffsetY, safeArea.top + halfHeight, safeArea.bottom - halfHeight);
        if (Math.abs(cx - bounds.centerX()) < 0.5f && Math.abs(cy - bounds.centerY()) < 0.5f) return;
        bounds.offsetTo(cx - halfWidth, cy - halfHeight);
        selected.positionX = (cx - safeArea.left) / safeArea.width();
        selected.positionY = (cy - safeArea.top) / safeArea.height();
        invalidate();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private boolean handleTouch(Control control, MotionEvent event) {
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        float left = control.target.getLeft();
        float top = control.target.getTop();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float px = left + event.getX(index), py = top + event.getY(index);
                if (event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE ||
                    !control.hits(px, py)) return false;
                control.pointer = id;
                update(control, px, py);
                break;
            case MotionEvent.ACTION_MOVE:
                int pointerIndex = event.findPointerIndex(control.pointer);
                if (pointerIndex >= 0) {
                    update(control, left + event.getX(pointerIndex), top + event.getY(pointerIndex));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (control.pointer == id) {
                    control.pointer = -1;
                    control.x = control.y = 0;
                    control.direction = 0;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseInput();
                return true;
            default:
                return false;
        }
        publishInput(0);
        invalidate();
        return true;
    }

    private void publishInput(int extraButtons) {
        int buttons = dpad.direction | extraButtons;
        for (Control pressed : controls) {
            if (pressed != menu && pressed.pointer != -1) {
                buttons |= pressed == start && titleScreen ? SKIP_INTRO : pressed.button;
            }
        }
        nativeSetInput(buttons, circlePad.x, -circlePad.y, cStick.x, -cStick.y, false);
    }

    private static void update(Control control, float px, float py) {
        float dx = px - control.bounds.centerX();
        float dy = py - control.bounds.centerY();
        if (control.shape == Shape.DPAD) {
            float deadZone = control.bounds.width() * 0.10f;
            float horizontal = Math.abs(dx), vertical = Math.abs(dy);
            control.direction = 0;
            if (horizontal > deadZone && horizontal >= vertical * 0.414f) {
                control.direction |= dx < 0 ? LEFT : RIGHT;
            }
            if (vertical > deadZone && vertical >= horizontal * 0.414f) {
                control.direction |= dy < 0 ? UP : DOWN;
            }
        } else if (control.shape == Shape.STICK) {
            float distance = (float) Math.hypot(dx, dy);
            float range = control.bounds.width() * 0.30f;
            float deadZone = range * 0.12f;
            float magnitude = Math.max(0, Math.min(1, (distance - deadZone) / (range - deadZone)));
            control.x = distance > 0 ? magnitude * dx / distance : 0;
            control.y = distance > 0 ? magnitude * dy / distance : 0;
        }
    }
}
