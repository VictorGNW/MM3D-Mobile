package org.triaevum.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

final class Mm3dTouchEditor extends LinearLayout {
    static final int MIN_OPACITY = 20;
    static final int MIN_SIZE = 65;
    static final int MAX_SIZE = 150;

    private final Button selection;
    private final SeekBar opacity;
    private final SeekBar size;

    Mm3dTouchEditor(Context context, Runnable save, Runnable cancel, Runnable reset,
                    String[] controls, IntConsumer selectControl,
                    IntConsumer changeOpacity, IntConsumer changeSize) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(8), dp(14), dp(8));
        setClickable(true); // Taps on the panel stay in the editor.
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF01B1C2D);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0xFF737389);
        setBackground(background);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header);
        header.addView(label("Arraste para mover"));
        selection = new Button(context, null, android.R.attr.borderlessButtonStyle);
        selection.setTextColor(Color.WHITE);
        selection.setAllCaps(false);
        header.addView(selection, new LayoutParams(0, dp(44), 1));
        selection.setOnClickListener(anchor -> {
            PopupMenu menu = new PopupMenu(context, anchor);
            for (int i = 0; i < controls.length; i++) menu.getMenu().add(0, i, i, controls[i]);
            menu.setOnMenuItemClickListener(item -> {
                selectControl.accept(item.getItemId());
                return true;
            });
            menu.show();
        });
        opacity = addSlider("Opacidade", MIN_OPACITY, 100, changeOpacity);
        size = addSlider("Tamanho", MIN_SIZE, MAX_SIZE, changeSize);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addView(actions);
        addButton(actions, "Restaurar padrão", reset);
        addButton(actions, "Cancelar", cancel);
        addButton(actions, "Salvar", save);
    }

    void showSelection(String name, int opacityPercent, int sizePercent) {
        selection.setText(name + " ▾");
        opacity.setProgress(opacityPercent - MIN_OPACITY);
        size.setProgress(sizePercent - MIN_SIZE);
    }

    private SeekBar addSlider(String name, int min, int max, IntConsumer change) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row);
        TextView caption = label(name);
        row.addView(caption, new LayoutParams(dp(86), LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(getContext());
        bar.setMax(max - min);
        bar.setContentDescription(name);
        ColorStateList accent = ColorStateList.valueOf(0xFFD4A143);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        row.addView(bar, new LayoutParams(0, dp(40), 1));

        TextView value = label("");
        value.setGravity(Gravity.END);
        row.addView(value, new LayoutParams(dp(44), LayoutParams.WRAP_CONTENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                int percent = min + progress;
                value.setText(percent + "%");
                if (fromUser) change.accept(percent);
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        return bar;
    }

    private TextView label(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        return view;
    }

    private void addButton(LinearLayout row, String name, Runnable action) {
        Button button = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        button.setText(name);
        button.setTextColor(name.equals("Salvar") ? 0xFFFFCD75 : Color.WHITE);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
