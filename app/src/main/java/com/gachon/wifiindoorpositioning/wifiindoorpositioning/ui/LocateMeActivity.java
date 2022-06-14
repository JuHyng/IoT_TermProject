package com.gachon.wifiindoorpositioning.wifiindoorpositioning.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.gachon.wifiindoorpositioning.wifiindoorpositioning.R;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.adapter.NearbyReadingsAdapter;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.core.Algorithms;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.core.WifiService;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.IndoorProject;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.LocDistance;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.LocationWithNearbyPlaces;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.WifiData;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.utils.AppContants;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.utils.Utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.realm.Realm;

/**
 * Created by suyashg on 10/09/17.
 */

public class LocateMeActivity extends AppCompatActivity {

    private WifiData mWifiData;
    private Algorithms algorithms = new Algorithms();
    private String projectId, defaultAlgo;
    private IndoorProject project;
    private MainActivityReceiver mReceiver = new MainActivityReceiver();
    private Intent wifiServiceIntent;
    private TextView tvLocation, tvNearestLocation, tvDistance;
    private RecyclerView rvPoints;
    private LinearLayoutManager layoutManager;
    private NearbyReadingsAdapter readingsAdapter = new NearbyReadingsAdapter();

    private TimeTableLayout timeTableLayout;

    private String className;
    private LocalDate currentDate;
    private LocalTime currentTime;
    private DayOfWeek today;
    private String time;

    private String[] dayToday = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"};
    private String[] dayToKorDay = {"월", "화", "수", "목", "금"};


    Resources res;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mWifiData = null;

        // set receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, new IntentFilter(AppContants.INTENT_FILTER));

        // launch WiFi service
        wifiServiceIntent = new Intent(this, WifiService.class);
        startService(wifiServiceIntent);

        // recover retained object
        mWifiData = (WifiData) getLastNonConfigurationInstance();

        //date and time
        currentDate = LocalDate.now();
        today = currentDate.getDayOfWeek();
        currentTime = LocalTime.now();
        time = String.valueOf(currentTime.getHour());

        // set layout
        setContentView(R.layout.activity_locate_me);
        initUI();

        defaultAlgo = Utils.getDefaultAlgo(this);
        projectId = getIntent().getStringExtra("projectId");
        if (projectId == null) {
            Toast.makeText(getApplicationContext(), "Project Not Found", Toast.LENGTH_LONG).show();
            this.finish();
        }
        Realm realm = Realm.getDefaultInstance();
        project = realm.where(IndoorProject.class).equalTo("id", projectId).findFirst();
        Log.v("LocateMeActivity", "onCreate");
    }

    private void initUI() {
        layoutManager = new LinearLayoutManager(this);
        tvLocation = findViewById(R.id.tv_location);
        tvNearestLocation = findViewById(R.id.tv_nearest_location);
        tvDistance = findViewById(R.id.tv_distance_origin);
        rvPoints = findViewById(R.id.rv_nearby_points);
        rvPoints.setLayoutManager(layoutManager);
        rvPoints.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvPoints.setAdapter(readingsAdapter);
        timeTableLayout = findViewById(R.id.timetable);
        updateTimeTable(className, today, time);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return mWifiData;
    }

    public class MainActivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v("LocateMeActivity", "MainActivityReceiver");
            mWifiData = (WifiData) intent.getParcelableExtra(AppContants.WIFI_DATA);

            if (mWifiData != null) {
                LocationWithNearbyPlaces loc = Algorithms.processingAlgorithms(mWifiData.getNetworks(), project, Integer.parseInt(defaultAlgo));
                Log.v("LocateMeActivity", "loc:" + loc);
                if (loc == null) {
                    tvLocation.setText("Location: NA\nNote:Please switch on your wifi and location services with permission provided to App");
                } else {
                    String locationValue = Utils.reduceDecimalPlaces(loc.getLocation());
                    tvLocation.setText("Location: " + locationValue);
                    String theDistancefromOrigin = Utils.getTheDistancefromOrigin(loc.getLocation());
                    tvDistance.setText("The distance from stage area is: " + theDistancefromOrigin + "m");
                    LocDistance theNearestPoint = Utils.getTheNearestPoint(loc);
                    if (theNearestPoint != null) {
                        className = theNearestPoint.getName();
                        tvNearestLocation.setText("You are near to: " + className);

                    }
                    readingsAdapter.setReadings(loc.getPlaces());
                    readingsAdapter.notifyDataSetChanged();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        stopService(wifiServiceIntent);
    }

    protected void updateTimeTable(String className, DayOfWeek dayOfWeek, String time) {
        res = getResources();
        String timeTableOfTheDay;
        String[] classTimeTable = res.getStringArray(R.array.class_415);
//        )

        String[][] classes = new String[5][11];

        int num = Arrays.asList(dayToday).indexOf(today.toString());

        for (int day = 0; day < 5; day++) {
            //multiple classes
            if (classTimeTable[day].contains("#")) {
                classes[day] = classTimeTable[day].split("#");
                for (int i = 0; i < classes[day].length; i++) {
                    if (classes[day][i].contains("/")) addClass(classes[day][i], day);
                }
            }
            //single class
            else {
                classes[day][0] = classTimeTable[day];
                if (classes[day][0].contains("/")) addClass(classes[day][0], day);
                }
            }
    }

    protected void addClass(String theClass, int day) {
        String[] parameter = theClass.split("/");

        timeTableLayout.addSchedule(parameter[0], parameter[1], dayToKorDay[day], Integer.parseInt(parameter[2]));
    }

}
