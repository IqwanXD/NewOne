package ru.iqwanoino.music;

import android.app.Activity;
import android.os.Build;
import android.graphics.Typeface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

public class Debuger extends Activity {
	
	private static final Map<String, String> exceptionMap = new HashMap<>();
	static {
		exceptionMap.put("StringIndexOutOfBoundsException", "Invalid string operation\n");
		exceptionMap.put("IndexOutOfBoundsException", "Invalid list operation\n");
		exceptionMap.put("ArithmeticException", "Invalid arithmetical operation\n");
		exceptionMap.put("NumberFormatException", "Invalid toNumber block operation\n");
		exceptionMap.put("ActivityNotFoundException", "Invalid intent operation\n");
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
		WindowManager.LayoutParams.FLAG_FULLSCREEN);
		
		super.onCreate(savedInstanceState);
		
		getWindow().getDecorView().setBackgroundColor(Color.parseColor("#121212"));
		
		String error = getIntent() != null ? getIntent().getStringExtra("error") : "";
		SpannableStringBuilder message = new SpannableStringBuilder();
		
		if (!error.isEmpty()) {
			String[] lines = error.split("\n");
			String summary = exceptionMap.getOrDefault(lines[0], "");
			
			if (!summary.isEmpty()) {
				message.append(summary);
			}
			
			for (int i = 1; i < lines.length; i++) {
				message.append(lines[i]).append("\n");
			}
		} else {
			message.append("No error message available.");
		}
		
		TextView title = new TextView(this);
		title.setText(
		"+====================+\n" +
		"| ⚠️ Error Detected! |\n" +
		"+====================+"
		);
		title.setTextSize(17);
		title.setTextColor(Color.parseColor("#3F51B5"));
		title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
		title.setPadding(16, 16, 16, 8);
		
		TextView textView = new TextView(this);
		textView.setText(message);
		textView.setTextSize(13);
		textView.setTextColor(Color.parseColor("#E0E0E0"));
		textView.setTypeface(Typeface.MONOSPACE);
		textView.setPadding(16, 0, 16, 16);
		textView.setTextIsSelectable(true);
		
		Button closeButton = new Button(this);
		closeButton.setText("Close");
		closeButton.setTextSize(16);
		closeButton.setPadding(16, 16, 16, 16);
		closeButton.setBackgroundColor(Color.parseColor("#3F51B5"));
		closeButton.setTextColor(Color.WHITE);
		closeButton.setTypeface(Typeface.DEFAULT_BOLD);
		closeButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					finishAndRemoveTask();
				} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
					finishAffinity();
				} else {
					finish();
				}
			}
		});
		
		LinearLayout linearLayout = new LinearLayout(this);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		linearLayout.addView(title);
		linearLayout.addView(textView);
		linearLayout.addView(closeButton);
		
		ScrollView verticalScroll = new ScrollView(this);
		HorizontalScrollView horizontalScroll = new HorizontalScrollView(this);
		verticalScroll.setVerticalScrollBarEnabled(false);
		horizontalScroll.setHorizontalScrollBarEnabled(false);
		
		verticalScroll.addView(linearLayout);
		horizontalScroll.addView(verticalScroll);
		
		setContentView(horizontalScroll);
	}
}