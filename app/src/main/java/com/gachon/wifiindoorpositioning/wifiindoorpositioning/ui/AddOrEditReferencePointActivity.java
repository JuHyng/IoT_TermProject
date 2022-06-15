package com.gachon.wifiindoorpositioning.wifiindoorpositioning.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.gachon.wifiindoorpositioning.wifiindoorpositioning.R;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.adapter.ReferenceReadingsAdapter;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.AccessPoint;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.IndoorProject;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.model.ReferencePoint;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.utils.AppContants;
import com.gachon.wifiindoorpositioning.wifiindoorpositioning.utils.Utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.realm.Realm;
import io.realm.RealmList;

/**
 * Created by suyashg on 07/09/17.
 */

public class AddOrEditReferencePointActivity extends AppCompatActivity implements View.OnClickListener {

    private String TAG = "AddOrEditReferencePointActivity";
    private String projectId;

    private RecyclerView rvPoints;
    private LinearLayoutManager layoutManager;


    private ReferenceReadingsAdapter readingsAdapter = new ReferenceReadingsAdapter();
    private List<AccessPoint> apsWithReading = new ArrayList<>();
    private Map<String, List<Integer>> readings = new HashMap<>();
    private Map<String, AccessPoint> aps = new HashMap<>();

    private AvailableAPsReceiver receiverWifi;

    private boolean wifiWasEnabled;
    private WifiManager mainWifi;
    private final Handler handler = new Handler();
    private boolean isCaliberating = false;
    private int readingsCount = 0;
    private boolean isEdit = false;
    private String rpId;
    private ReferencePoint referencePointFromDB;

    private AccessPoint max_ap;

    private TimeTableLayout timeTableLayout;
    private TextView tvNearestLocation, tvTime;

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
        setContentView(R.layout.activity_add_reference_point);

        //date and time
        currentDate = LocalDate.now();
        today = currentDate.getDayOfWeek();
        currentTime = LocalTime.now();
        time = String.valueOf(currentTime.getHour());

        projectId = getIntent().getStringExtra("projectId");
        if (projectId == null) {
            Toast.makeText(this, "Reference point not found", Toast.LENGTH_LONG).show();
            this.finish();
        }

        if (getIntent().getStringExtra("rpId") != null) {
            isEdit = true;
            rpId = getIntent().getStringExtra("rpId");
        }
        initUI();
        Realm realm = Realm.getDefaultInstance();
        if (isEdit) {
            referencePointFromDB = realm.where(ReferencePoint.class).equalTo("id", rpId).findFirst();
            if (referencePointFromDB == null) {
                Toast.makeText(this, "Reference point not found", Toast.LENGTH_LONG).show();
                this.finish();
            }
            RealmList<AccessPoint> readings = referencePointFromDB.getReadings();
            for (AccessPoint ap:readings) {
                readingsAdapter.addAP(ap);
            }
            readingsAdapter.notifyDataSetChanged();

        } else {
            mainWifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            receiverWifi = new AvailableAPsReceiver();
            wifiWasEnabled = mainWifi.isWifiEnabled();

            IndoorProject project = realm.where(IndoorProject.class).equalTo("id", projectId).findFirst();
            RealmList<AccessPoint> points = project.getAps();
            for (AccessPoint accessPoint : points) {
                aps.put(accessPoint.getMac_address(), accessPoint);
            }
            if (aps.isEmpty()) {
                Toast.makeText(this, "No Access Points Found", Toast.LENGTH_SHORT).show();
            }
            if (!Utils.isLocationEnabled(this)) {
                Toast.makeText(this,"Please turn on the location", Toast.LENGTH_SHORT).show();
            }
        }


    }

    @Override
    protected void onResume() {
        if (!isEdit) {
            registerReceiver(receiverWifi, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            Log.v(TAG, "caliberationStarted");
            if (!isCaliberating) {
                isCaliberating = true;
                refresh();
            }
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        if (!isEdit) {
            unregisterReceiver(receiverWifi);
            isCaliberating = false;
        }
        super.onPause();
    }

    public void refresh() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mainWifi.startScan();
                if (readingsCount < AppContants.READINGS_BATCH) {
                    refresh();
                } else {
                    caliberationCompleted();
                }
            }
        }, AppContants.FETCH_INTERVAL);
    }

    private void caliberationCompleted() {
        isCaliberating = false;
        Log.v(TAG, "caliberationCompleted");
        Map<String, List<Integer>> values = readings;
        Log.v(TAG, "values:"+values.toString());
        for (Map.Entry<String, List<Integer>> entry : values.entrySet()) {
            List<Integer> readingsOfAMac = entry.getValue();
            Double mean = calculateMeanValue(readingsOfAMac);
            Log.v(TAG, "entry.Key:"+entry.getKey()+" aps:"+aps);
            AccessPoint accessPoint = aps.get(entry.getKey());
            AccessPoint updatedPoint = new AccessPoint(accessPoint);
            updatedPoint.setMeanRss(mean);
            apsWithReading.add(updatedPoint);
        }
        readingsAdapter.setReadings(apsWithReading);
        readingsAdapter.notifyDataSetChanged();


        int i = 0;
        for (AccessPoint ap:apsWithReading) {
            if (i == 0) max_ap = ap;
            else {
                if (ap.getMeanRss() >= max_ap.getMeanRss()) {
                    max_ap = ap;
                }
            }
        }

        String result = max_ap.getSsid();
        tvNearestLocation.setText(result);
        updateTimeTable(result, today, time);
    }

    private Double calculateMeanValue(List<Integer> readings) {
        if (readings.isEmpty()) {
            return 0.0d;
        }
        Integer sum = 0;
        for (Integer integer : readings) {
            sum = sum + integer;
        }
        double mean = Double.valueOf(sum) / Double.valueOf(readings.size());
        return mean;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initUI() {
        timeTableLayout = findViewById(R.id.timetable);
        tvNearestLocation = findViewById(R.id.tv_nearest_location);
        tvTime = findViewById(R.id.tv_time);

        int num = Arrays.asList(dayToday).indexOf(today.toString());
        tvTime.setText(dayToKorDay[num] + "요일 " + currentTime.getHour() + "시 " + currentTime.getMinute() + "분");
    }

    @Override
    public void onClick(View view) {
        if (!isEdit) {
            Realm realm = Realm.getDefaultInstance();
            realm.beginTransaction();
            ReferencePoint referencePoint = new ReferencePoint();
            referencePoint = setValues(referencePoint);
            referencePoint.setCreatedAt(Calendar.getInstance().getTime());
            referencePoint.setDescription("");
//            apsWithReading = realm.copyToRealmOrUpdate(apsWithReading);
            if (referencePoint.getReadings() == null) {
                RealmList<AccessPoint> readings = new RealmList<>();
                readings.addAll(apsWithReading);
                referencePoint.setReadings(readings);
            } else {
                referencePoint.getReadings().addAll(apsWithReading);
            }

            referencePoint.setId(UUID.randomUUID().toString());

            IndoorProject project = realm.where(IndoorProject.class).equalTo("id", projectId).findFirst();
            if (project.getRps() == null) {
                RealmList<ReferencePoint> points = new RealmList<>();
                points.add(referencePoint);
                project.setRps(points);
            } else {
                project.getRps().add(referencePoint);
            }

            realm.commitTransaction();
            Toast.makeText(this,"Reference Point Added", Toast.LENGTH_SHORT).show();
            this.finish();
        } else if (isEdit) {
            Realm realm = Realm.getDefaultInstance();
            realm.beginTransaction();
            referencePointFromDB = setValues(referencePointFromDB);
            realm.commitTransaction();
            Toast.makeText(this,"Reference Point Updated", Toast.LENGTH_SHORT).show();
            this.finish();
        }
    }

    private ReferencePoint setValues(ReferencePoint referencePoint) {
        String x = "0";
        String y = "0";
        if (TextUtils.isEmpty(x)) {
            referencePoint.setX(0.0d);
        } else {
            referencePoint.setX(Double.valueOf(x));
        }

        if (TextUtils.isEmpty(y)) {
            referencePoint.setY(0.0d);
        } else {
            referencePoint.setY(Double.valueOf(y));
        }
        referencePoint.setLocId(referencePoint.getX() + " " + referencePoint.getY());
        referencePoint.setName("");
        return referencePoint;
    }

    class AvailableAPsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<ScanResult> scanResults = mainWifi.getScanResults();
            ++readingsCount;
            for (Map.Entry<String, AccessPoint> entry : aps.entrySet()) {
                String apMac = entry.getKey();
                for (ScanResult scanResult : scanResults) {
                    if (entry.getKey().equals(scanResult.BSSID)) {
                        checkAndAddApRSS(apMac, scanResult.level);
                        apMac = null;//do this after always :|
                        break;
                    }
                }
                if (apMac != null) {
                    checkAndAddApRSS(apMac, AppContants.NaN.intValue());
                }
            }
//            results.put(Calendar.getInstance(), map);

            Log.v(TAG, "Count:" + readingsCount+" scanResult:"+ scanResults.toString()+" aps:"+aps.toString());
            for (int i = 0; i < readingsCount; ++i) {
//                Log.v(TAG, "  BSSID       =" + results.get(i).BSSID);
//                Log.v(TAG, "  SSID        =" + results.get(i).SSID);
//                Log.v(TAG, "  Capabilities=" + results.get(i).capabilities);
//                Log.v(TAG, "  Frequency   =" + results.get(i).frequency);
//                Log.v(TAG, "  Level       =" + results.get(i).level);
//                Log.v(TAG, "---------------");
            }
        }
    }

    private void checkAndAddApRSS(String apMac, Integer level) {
        if (readings.containsKey(apMac)) {
            List<Integer> integers = readings.get(apMac);
            integers.add(level);
        } else {
            List<Integer> integers = new ArrayList<>();
            integers.add(level);
            readings.put(apMac, integers);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!wifiWasEnabled && !isEdit) {
            mainWifi.setWifiEnabled(false);
        }
    }

    protected void updateTimeTable(String className, DayOfWeek dayOfWeek, String time) {
        res = getResources();
        String timeTableOfTheDay;
        int resID;
        int classNum = Integer.parseInt(className.split("_")[1]);
        boolean hasTimeTable = false;

        if ((classNum >= 301)&&(classNum <= 307))
            hasTimeTable = true;
        if ((classNum >= 407)&& (classNum <= 415))
            hasTimeTable =true;
        if ((classNum >= 503)&&(classNum <= 505))
            hasTimeTable =true;
        if ((classNum >= 508)&&(classNum <= 511))
            hasTimeTable = true;



        if (hasTimeTable) {

            resID = res.getIdentifier(className, "array", this.getPackageName());


            String[] classTimeTable = res.getStringArray(resID);


            if (classTimeTable.length > 0) {

                String[][] classes = new String[5][11];


                for (int day = 0; day < 5; day++) {
                    //multiple classes
                    if (classTimeTable[day].contains("#")) {
                        classes[day] = classTimeTable[day].split("#");
                        for (int i = 0; i < classes[day].length; i++) {
                            if (classes[day][i].contains("/")) addClass(classes[day][i], day, time);
                        }
                    }
                    //single class
                    else {
                        classes[day][0] = classTimeTable[day];
                        if (classes[day][0].contains("/")) addClass(classes[day][0], day, time);
                    }
                }
            }
        }
    }

    protected void addClass(String theClass, int day, String t) {
        String[] parameter = theClass.split("/");
        if ((isDuring(t, parameter[1], parameter[2]))&&(dayToday[day].equals(today.toString()))) {
            timeTableLayout.addSchedule(parameter[0], parameter[1], dayToKorDay[day], Integer.parseInt(parameter[2]), res.getColor(R.color.colorAccent), res.getColor(R.color.white));
        }
        else {
            timeTableLayout.addSchedule(parameter[0], parameter[1], dayToKorDay[day], Integer.parseInt(parameter[2]));
        }
    }

    protected boolean isDuring(String t, String param1, String param2) {
        int time = Integer.parseInt(t) - 8;
        int classStart = Integer.parseInt(param1);
        int block = Integer.parseInt(param2);

        return (time >= classStart)&&(time <= (classStart+block));
    }
}
