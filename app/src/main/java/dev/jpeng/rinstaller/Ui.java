package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toolbar;

final class Ui {
    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout page(Activity activity) {
        LinearLayout root = screenRoot(activity);
        root.addView(toolbar(activity, activity.getTitle().toString(), true));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return content;
    }

    static LinearLayout screenRoot(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(activity.getColor(R.color.surface));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        activity.setContentView(root);
        return root;
    }

    static Toolbar toolbar(Activity activity, String title, boolean showBack) {
        Toolbar toolbar = new Toolbar(activity);
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(activity.getColor(R.color.text_primary));
        toolbar.setTitleTextAppearance(activity, R.style.ToolbarTitle);
        toolbar.setBackgroundColor(activity.getColor(R.color.surface));
        toolbar.setContentInsetsRelative(dp(activity, 16), dp(activity, 8));
        if (showBack) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            toolbar.setNavigationContentDescription(R.string.back);
            toolbar.setNavigationOnClickListener(view -> activity.finish());
        }
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)));
        return toolbar;
    }

    static TextView title(Context context, String text) {
        TextView view = text(context, text, 28);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(context, 16));
        return view;
    }

    static TextView heading(Context context, String text) {
        TextView view = text(context, text, 18);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(context, 18), 0, dp(context, 6));
        return view;
    }

    static TextView text(Context context, String text, int sp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(context.getColor(R.color.text_primary));
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    static Button button(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 10);
        button.setLayoutParams(params);
        return button;
    }

    static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static LinearLayout homeCard(
            Context context,
            int backgroundResource,
            int iconBackgroundResource,
            int iconResource,
            String title,
            String summary,
            View.OnClickListener listener
    ) {
        LinearLayout card = row(context);
        card.setMinimumHeight(dp(context, 96));
        card.setPadding(dp(context, 16), dp(context, 16),
                dp(context, 16), dp(context, 16));
        card.setBackgroundResource(backgroundResource);
        card.setClickable(true);
        card.setFocusable(true);
        card.setForeground(selectableBackground(context));
        card.setOnClickListener(listener);

        FrameLayout circle = new FrameLayout(context);
        circle.setBackgroundResource(iconBackgroundResource);
        card.addView(circle, new LinearLayout.LayoutParams(
                dp(context, 48), dp(context, 48)));

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconResource);
        icon.setImageTintList(context.getColorStateList(R.color.icon_foreground));
        circle.addView(icon, new FrameLayout.LayoutParams(
                dp(context, 27), dp(context, 27), Gravity.CENTER));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        copyParams.setMarginStart(dp(context, 16));
        card.addView(copy, copyParams);

        TextView titleView = text(context, title, 17);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copy.addView(titleView);

        TextView summaryView = text(context, summary, 15);
        summaryView.setTextColor(context.getColor(R.color.text_secondary));
        summaryView.setPadding(0, dp(context, 3), 0, 0);
        copy.addView(summaryView);
        return card;
    }

    static Drawable selectableBackground(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, value, true);
        return context.getDrawable(value.resourceId);
    }
}
