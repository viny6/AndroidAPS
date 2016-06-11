package info.nightscout.androidaps.plugins.OpenAPSMA;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.squareup.otto.Subscribe;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainActivity;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Iob;
import info.nightscout.androidaps.data.Pump;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventTreatmentChange;
import info.nightscout.androidaps.plugins.APSBase;
import info.nightscout.androidaps.plugins.APSResult;
import info.nightscout.androidaps.plugins.PluginBase;
import info.nightscout.androidaps.plugins.ScriptReader;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsFragment;
import info.nightscout.client.data.NSProfile;
import info.nightscout.utils.DateUtil;

public class OpenAPSMAFragment extends Fragment implements View.OnClickListener, PluginBase, APSBase {
    private static Logger log = LoggerFactory.getLogger(OpenAPSMAFragment.class);

    Button run;
    TextView lastRunView;
    TextView glucoseStatusView;
    TextView currentTempView;
    TextView iobDataView;
    TextView profileView;
    TextView mealDataView;
    TextView resultView;

    Date lastAPSRun = null;
    APSResult lastAPSResult = null;

    @Override
    public int getType() {
        return PluginBase.APS;
    }

    @Override
    public boolean isFragmentVisible() {
        return true;
    }

    @Override
    public APSResult getLastAPSResult() {
        return lastAPSResult;
    }

    @Override
    public Date getLastAPSRun() {
        return lastAPSRun;
    }

    public static OpenAPSMAFragment newInstance() {
        OpenAPSMAFragment fragment = new OpenAPSMAFragment();
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerBus();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.openapsma_fragment, container, false);

        run = (Button) view.findViewById(R.id.openapsma_run);
        run.setOnClickListener(this);
        lastRunView = (TextView) view.findViewById(R.id.openapsma_lastrun);
        glucoseStatusView = (TextView) view.findViewById(R.id.openapsma_glucosestatus);
        currentTempView = (TextView) view.findViewById(R.id.openapsma_currenttemp);
        iobDataView = (TextView) view.findViewById(R.id.openapsma_iobdata);
        profileView = (TextView) view.findViewById(R.id.openapsma_profile);
        mealDataView = (TextView) view.findViewById(R.id.openapsma_mealdata);
        resultView = (TextView) view.findViewById(R.id.openapsma_result);

        return view;
    }

    private void registerBus() {
        try {
            MainApp.bus().unregister(this);
        } catch (RuntimeException x) {
            // Ignore
        }
        MainApp.bus().register(this);
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.openapsma_run:
                invoke();
                break;
        }

    }

    @Subscribe
    public void onStatusEvent(final EventTreatmentChange ev) {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    invoke();
                }
            });
        else
            log.debug("EventTreatmentChange: Activity is null");
    }

    @Subscribe
    public void onStatusEvent(final EventNewBG ev) {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    invoke();
                }
            });
        else
            log.debug("EventNewBG: Activity is null");
    }

    @Override
    public void invoke() {

        //   private DatermineBasalResult openAps(int glucoseValue, int delta, double deltaAvg15min, StatusEvent status, LowSuspendStatus lowSuspendStatus, IobTotal iobTotal, CarbCalc.Meal mealdata) {
        DetermineBasalAdapterJS determineBasalAdapterJS = null;
        try {
            determineBasalAdapterJS = new DetermineBasalAdapterJS(new ScriptReader(MainApp.instance().getBaseContext()));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return;
        }

        DatabaseHelper.GlucoseStatus glucoseStatus = MainApp.getDbHelper().getGlucoseStatusData();
        NSProfile profile = MainApp.getNSProfile();
        Pump pump = MainApp.getActivePump();

        if (glucoseStatus == null) {
            if (Config.logAPSResult) log.debug("No glucose data available");
            return;
        }

        if (profile == null) {
            if (Config.logAPSResult) log.debug("No profile available");
            return;
        }

        if (pump == null) {
            if (Config.logAPSResult) log.debug("No pump available");
            return;
        }

        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences(MainApp.instance().getApplicationContext());
        String units = profile.getUnits();

        String maxBgDefault = "180";
        String minBgDefault = "100";
        if (!units.equals(Constants.MGDL)) {
            maxBgDefault = "10";
            minBgDefault = "5";
        }

        // TODO: objectives limits
        double maxIob = Double.parseDouble(SP.getString("max_iob", "1.5").replace(",", "."));
        double maxBasal = Double.parseDouble(SP.getString("max_basal", "1").replace(",", "."));
        // TODO: min_bg, max_bg in prefs
        double minBg = NSProfile.toMgdl(Double.parseDouble(SP.getString("min_bg", minBgDefault).replace(",", ".")), units);
        double maxBg = NSProfile.toMgdl(Double.parseDouble(SP.getString("max_bg", maxBgDefault).replace(",", ".")), units);

        MainActivity.treatmentsFragment.updateTotalIOBIfNeeded();
        MainActivity.tempBasalsFragment.updateTotalIOBIfNeeded();
        IobTotal bolusIob = MainActivity.treatmentsFragment.lastCalculation;
        IobTotal basalIob = MainActivity.tempBasalsFragment.lastCalculation;

        IobTotal iobTotal = IobTotal.combine(bolusIob, basalIob);

        TreatmentsFragment.MealData mealData = MainActivity.treatmentsFragment.getMealData();

        determineBasalAdapterJS.setData(profile, maxIob, maxBasal, minBg, maxBg, pump, iobTotal, glucoseStatus, mealData);

        glucoseStatusView.setText(determineBasalAdapterJS.getGlucoseStatusParam());
        currentTempView.setText(determineBasalAdapterJS.getCurrentTempParam());
        iobDataView.setText(determineBasalAdapterJS.getIobDataParam());
        profileView.setText(determineBasalAdapterJS.getProfileParam());
        mealDataView.setText(determineBasalAdapterJS.getMealDataParam());

        DetermineBasalResult determineBasalResult = determineBasalAdapterJS.invoke();

        resultView.setText(determineBasalResult.json.toString());
        lastRunView.setText(new Date().toLocaleString());

        determineBasalAdapterJS.release();

        try {
            determineBasalResult.json.put("timestamp", DateUtil.toISOString(new Date()));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        lastAPSResult = determineBasalResult;
        lastAPSRun = new Date();

        //deviceStatus.suggested = determineBasalResult.json;

    }
}
