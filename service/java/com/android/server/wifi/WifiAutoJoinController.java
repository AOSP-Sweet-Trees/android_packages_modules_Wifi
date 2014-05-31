/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.content.Context;

import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.WifiKey;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Date;

/**
 * AutoJoin controller is responsible for WiFi Connect decision
 *
 * It runs in the thread context of WifiStateMachine
 *
 */
public class WifiAutoJoinController {

    private Context mContext;
    private WifiStateMachine mWifiStateMachine;
    private WifiConfigStore mWifiConfigStore;
    private WifiTrafficPoller mWifiTrafficPoller;
    private WifiNative mWifiNative;

    private NetworkScoreManager scoreManager;
    private WifiNetworkScoreCache mNetworkScoreCache;

    private static final String TAG = "WifiAutoJoinController ";
    private static boolean DBG = false;
    private static boolean VDBG = false;
    private static final boolean mStaStaSupported = false;
    private static final int SCAN_RESULT_CACHE_SIZE = 80;

    private String mCurrentConfigurationKey = null; //used by autojoin

    private HashMap<String, ScanResult> scanResultCache =
            new HashMap<String, ScanResult>();

    WifiAutoJoinController(Context c, WifiStateMachine w, WifiConfigStore s,
                           WifiTrafficPoller t, WifiNative n) {
        mContext = c;
        mWifiStateMachine = w;
        mWifiConfigStore = s;
        mWifiTrafficPoller = t;
        mWifiNative = n;
        mNetworkScoreCache = null;
        scoreManager =
                (NetworkScoreManager) mContext.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (scoreManager == null)
            logDbg("Registered scoreManager NULL " + " service " + Context.NETWORK_SCORE_SERVICE);

        if (scoreManager != null) {
            mNetworkScoreCache = new WifiNetworkScoreCache(mContext);
            scoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache);
        } else {
            logDbg("No network score service: Couldnt register as a WiFi score Manager, type="
                    + Integer.toString(NetworkKey.TYPE_WIFI)
                    + " service " + Context.NETWORK_SCORE_SERVICE);
            mNetworkScoreCache = null;
        }
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0 ) {
            DBG = true;
            VDBG = true;
        } else {
            DBG = false;
            VDBG = false;
        }
    }

    int mScanResultMaximumAge = 30000; /* milliseconds unit */

    /*
     * flush out scan results older than mScanResultMaximumAge
     *
     * */
    private void ageScanResultsOut(int delay) {
        if (delay <= 0) {
            delay = mScanResultMaximumAge; //something sane
        }
        Date now = new Date();
        long milli = now.getTime();
        if (VDBG) {
            logDbg("ageScanResultsOut delay " + Integer.valueOf(delay) + " size "
                    + Integer.valueOf(scanResultCache.size()) + " now " + Long.valueOf(milli));
        }

        Iterator<HashMap.Entry<String,ScanResult>> iter = scanResultCache.entrySet().iterator();
        while (iter.hasNext()) {
            HashMap.Entry<String,ScanResult> entry = iter.next();
            ScanResult result = entry.getValue();

            if ((result.seen + delay) < milli) {
                iter.remove();
            }
        }
    }

    void addToScanCache(List<ScanResult> scanList) {
        WifiConfiguration associatedConfig;

        ArrayList<NetworkKey> unknownScanResults = new ArrayList<NetworkKey>();

        for(ScanResult result: scanList) {
            if (result.SSID == null) continue;
            result.seen = System.currentTimeMillis();

            ScanResult sr = scanResultCache.get(result.BSSID);
            if (sr != null) {
                // if there was a previous cache result for this BSSID, average the RSSI values

                int previous_rssi = sr.level;
                long previously_seen_milli = sr.seen;

                /* average RSSI with previously seen instances of this scan result */
                int avg_rssi = result.level;

                if ((previously_seen_milli > 0)
                        && (previously_seen_milli < mScanResultMaximumAge/2)) {
                    /*
                    *
                    * previously_seen_milli = 0 => RSSI = 0.5 * previous_seen_rssi + 0.5 * new_rssi
                    *
                    * If previously_seen_milli is 15+ seconds old:
                    *      previously_seen_milli = 15000 => RSSI = new_rssi
                    *
                    */

                    double alpha = 0.5 - (double)previously_seen_milli
                            / (double)mScanResultMaximumAge;

                    avg_rssi = (int)((double)avg_rssi * (1-alpha) + (double)previous_rssi * alpha);
                }
                result.level = avg_rssi;

                //remove the previous Scan Result
                scanResultCache.remove(result.BSSID);
            } else {
                if (!mNetworkScoreCache.isScoredNetwork(result)) {
                    WifiKey wkey;
                    //TODO : find out how we can get there without a valid UTF-8 encoded SSID
                    //TODO: which will cause WifiKey constructor to fail
                    try {
                        wkey = new WifiKey("\"" + result.SSID + "\"", result.BSSID);
                    } catch (IllegalArgumentException e) {
                        logDbg("AutoJoinController: received badly encoded SSID=[" + result.SSID +
                                "] ->skipping this network");
                        wkey = null;
                    }
                    if (wkey != null) {
                        NetworkKey nkey = new NetworkKey(wkey);
                        //if we don't know this scan result then request a score to Herrevad
                        unknownScanResults.add(nkey);
                    }
                }
            }

            scanResultCache.put(result.BSSID, new ScanResult(result));

            ScanResult srn = scanResultCache.get(result.BSSID);

            //add this BSSID to the scanResultCache of the relevant WifiConfiguration
            associatedConfig = mWifiConfigStore.updateSavedNetworkHistory(result);

            //try to associate this BSSID to an existing Saved WifiConfiguration
            if (associatedConfig == null) {
                associatedConfig = mWifiConfigStore.associateWithConfiguration(result);
                if (associatedConfig != null && associatedConfig.SSID != null) {
                    if (VDBG) {
                        logDbg("addToScanCache save associated config "
                                + associatedConfig.SSID + " with " + associatedConfig.SSID);
                    }
                    mWifiStateMachine.sendMessage(WifiManager.SAVE_NETWORK, associatedConfig);
                }
            }
        }

        if (unknownScanResults.size() != 0) {
            NetworkKey[] newKeys =
                    unknownScanResults.toArray(new NetworkKey[unknownScanResults.size()]);
                //kick the score manager, we will get updated scores asynchronously
            scoreManager.requestScores(newKeys);
        }
    }

    void logDbg(String message) {
        logDbg(message, false);
    }

    void logDbg(String message, boolean stackTrace) {
        long now = SystemClock.elapsedRealtimeNanos();
        String ts = String.format("[%,d us] ", now / 1000);
        if (stackTrace) {
            Log.e(TAG, ts + message + " stack:"
                    + Thread.currentThread().getStackTrace()[2].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[3].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[4].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[5].getMethodName());
        } else {
            Log.e(TAG, ts + message);
        }
    }

    /* called directly from WifiStateMachine  */
    void newSupplicantResults() {
        List<ScanResult> scanList = mWifiStateMachine.syncGetScanResultsList();
        addToScanCache(scanList);
        ageScanResultsOut(mScanResultMaximumAge);
        if (DBG)
           logDbg("newSupplicantResults size=" + Integer.valueOf(scanResultCache.size()) );

        attemptAutoJoin();
        mWifiConfigStore.writeKnownNetworkHistory();

    }


    /* not used at the moment
     * should be a call back from WifiScanner HAL ??
     * this function is not hooked and working yet, it will receive scan results from WifiScanners
     * with the list of IEs,then populate the capabilities by parsing the IEs and inject the scan
     * results as normal.
     */
    void newHalScanResults() {
        List<ScanResult> scanList = null;//mWifiScanner.syncGetScanResultsList();
        String akm = WifiParser.parse_akm(null, null);
        logDbg(akm);
        addToScanCache(scanList);
        ageScanResultsOut(0);
        attemptAutoJoin();
        mWifiConfigStore.writeKnownNetworkHistory();
    }

    /* network link quality changed, called directly from WifiTrafficPoller,
    or by listening to Link Quality intent */
    void linkQualitySignificantChange() {
        attemptAutoJoin();
    }

    /*
     * compare a WifiConfiguration against the current network, return a delta score
     * If not associated, and the candidate will always be better
     * For instance if the candidate is a home network versus an unknown public wifi,
     * the delta will be infinite, else compare Kepler scores etc…
     ***/
    private int compareNetwork(WifiConfiguration candidate) {
        WifiConfiguration currentNetwork = mWifiStateMachine.getCurrentWifiConfiguration();
        if (currentNetwork == null)
            return 1000;

        if (candidate.configKey(true).equals(currentNetwork.configKey(true))) {
            return -1;
        }

        int order = compareWifiConfigurations(currentNetwork, candidate);

        if (order > 0) {
            //ascending: currentNetwork < candidate
            return 10; //will try switch over to the candidate
        }

        return 0;
    }

    /**
     * update the network history fields fo that configuration
     * - if userTriggered, we mark the configuration as "non selfAdded" since the user has seen it
     * and took over management
     * - if it is a "connect", remember which network were there at the point of the connect, so
     * as those networks get a relative lower score than the selected configuration
     *
     * @param netId
     * @param userTriggered : if the update come from WiFiManager
     * @param connect : if the update includes a connect
     **/
    public void updateConfigurationHistory(int netId, boolean userTriggered, boolean connect) {
        WifiConfiguration selected = mWifiConfigStore.getWifiConfiguration(netId);
        if (selected == null) {
            return;
        }

        if (selected.SSID == null) {
            return;
        }

        if (userTriggered) {
            // reenable autojoin for this network,
            // since the user want to connect to this configuration
            selected.autoJoinStatus = WifiConfiguration.AUTO_JOIN_ENABLED;
            selected.selfAdded = false;
        }

        if (DBG && userTriggered) {
            if (selected.connectChoices != null) {
                logDbg("updateConfigurationHistory will update "
                        + Integer.toString(netId) + " now: "
                        + Integer.toString(selected.connectChoices.size())
                        + " uid=" + Integer.toString(selected.creatorUid), true);
            } else {
                logDbg("updateConfigurationHistory will update "
                        + Integer.toString(netId)
                        + " uid=" + Integer.toString(selected.creatorUid), true);
            }
        }

        if (connect && userTriggered) {
            boolean found = false;
            List<WifiConfiguration> networks =
                    mWifiConfigStore.getRecentConfiguredNetworks(12000, false);
            if (networks != null) {
                for (WifiConfiguration config : networks) {
                    if (DBG) {
                        logDbg("updateConfigurationHistory got " + config.SSID + " nid="
                                + Integer.toString(config.networkId));
                    }

                    if (selected.configKey(true).equals(config.configKey(true))) {
                        found = true;
                        continue;
                    }

                    int rssi = WifiConfiguration.INVALID_RSSI;
                    if (config.visibility != null) {
                        rssi = config.visibility.rssi5;
                        if (config.visibility.rssi24 > rssi)
                            rssi = config.visibility.rssi24;
                    }
                    if (rssi < -80) {
                        continue;
                    }

                    //the selected configuration was preferred over a recently seen config
                    //hence remember the user's choice:
                    //add the recently seen config to the selected's connectChoices array

                    if (selected.connectChoices == null) {
                        selected.connectChoices = new HashMap<String, Integer>();
                    }

                    logDbg("updateConfigurationHistory add a choice " + selected.configKey(true)
                            + " over " + config.configKey(true)
                            + " RSSI " + Integer.toString(rssi));
                    //add the visible config to the selected's connect choice list
                    selected.connectChoices.put(config.configKey(true), rssi);

                    if (config.connectChoices != null) {
                        if (VDBG) {
                            logDbg("updateConfigurationHistory will remove "
                                    + selected.configKey(true) + " from " + config.configKey(true));
                        }
                        //remove the selected from the recently seen config's connectChoice list
                        config.connectChoices.remove(selected.configKey(true));

                        if (selected.linkedConfigurations != null) {
                           //remove the selected's linked configuration from the
                           //recently seen config's connectChoice list
                           for (String key : selected.linkedConfigurations.keySet()) {
                               config.connectChoices.remove(key);
                           }
                        }
                    }
                }
                if (found == false) {
                     // log an error for now but do something stringer later
                     // we will need a new scan before attempting to connect to this
                     // configuration anyhow and thus we can process the scan results then
                     logDbg("updateConfigurationHistory try to connect to an old network!! : "
                             + selected.configKey());
                }

                if (selected.connectChoices != null) {
                    if (VDBG)
                        logDbg("updateConfigurationHistory " + Integer.toString(netId)
                                + " now: " + Integer.toString(selected.connectChoices.size()));
                }

            }
        }

        //TODO: write only if something changed
        if (userTriggered || connect) {
            mWifiConfigStore.writeKnownNetworkHistory();
        }
    }

    void printChoices(WifiConfiguration config) {
        int num = 0;
        if (config.connectChoices!= null) {
            num = config.connectChoices.size();
        }

        logDbg("printChoices " + config.SSID + " num choices: " + Integer.toString(num));
        if (config.connectChoices!= null) {
            for (String key : config.connectChoices.keySet()) {
                logDbg("                 " + key);
            }
        }
    }

    boolean hasConnectChoice(WifiConfiguration source, WifiConfiguration target) {
        boolean found = false;
        if (source == null)
            return false;
        if (target == null)
            return false;

        if (source.connectChoices != null) {
            if ( source.connectChoices.get(target.configKey(true)) != null) {
                found = true;
            }
        }

        if (source.linkedConfigurations != null) {
            for (String key : source.linkedConfigurations.keySet()) {
                WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(key);
                if (config != null) {
                    if (config.connectChoices != null) {
                        if (config.connectChoices.get(target.configKey(true)) != null) {
                            found = true;
                        }
                    }
                }
            }
        }
        return found;
    }

    int compareWifiConfigurationsRSSI(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;
        int boost5 = 25;

        WifiConfiguration.Visibility astatus = a.visibility;
        WifiConfiguration.Visibility bstatus = b.visibility;
        if (astatus == null || bstatus == null) {
            //error -> cant happen, need to throw en exception
            logDbg("compareWifiConfigurations NULL band status!");
            return 0;
        }
        if ((astatus.rssi5 > -70) && (bstatus.rssi5 == WifiConfiguration.INVALID_RSSI)
                && ((astatus.rssi5+boost5) > (bstatus.rssi24))) {
            //a is seen on 5GHz with good RSSI, greater rssi than b
            //a is of higher priority - descending
            order = -1;
        } else if ((bstatus.rssi5 > -70) && (astatus.rssi5 == WifiConfiguration.INVALID_RSSI)
                && ((bstatus.rssi5+boost5) > (bstatus.rssi24))) {
            //b is seen on 5GHz with good RSSI, greater rssi than a
            //a is of lower priority - ascending
            order = 1;
        }
        return order;
    }

    int compareWifiConfigurations(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;
        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();
        boolean linked = false;

        if ((a.linkedConfigurations != null) && (b.linkedConfigurations != null)
                && (a.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)
                && (b.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)) {
            if ((a.linkedConfigurations.get(b.configKey(true))!= null)
                    && (b.linkedConfigurations.get(a.configKey(true))!= null)) {
                linked = true;
            }
        }

        if (a.ephemeral && b.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " + b.configKey()
                        + " over " + a.configKey());
            }
            return 1; //b is of higher priority - ascending
        }
        if (b.ephemeral && a.ephemeral == false) {
            if (VDBG) {
                logDbg("compareWifiConfigurations ephemeral and prefers " +a.configKey()
                        + " over " + b.configKey());
            }
            return -1; //a is of higher priority - descending
        }

        int boost5 = 25;
        //apply Hysteresis: boost the RSSI value of the currently connected configuration
        int aRssiBoost = 0;
        int bRssiBoost = 0;
        if (null != mCurrentConfigurationKey) {
            if (a.configKey().equals(mCurrentConfigurationKey)) {
                aRssiBoost += 10;
            } else if (b.configKey().equals(mCurrentConfigurationKey)) {
                bRssiBoost += 10;
            }
        }
        if (linked) {
            // then we try prefer 5GHz, and try to ignore user's choice
            WifiConfiguration.Visibility astatus = a.visibility;
            WifiConfiguration.Visibility bstatus = b.visibility;
            if (astatus == null || bstatus == null) {
                //error
                logDbg("compareWifiConfigurations NULL band status!");
                return 0;
            }

            if (VDBG)  {
                logDbg("compareWifiConfigurations linked: " + Integer.toString(astatus.rssi5)
                        + "," + Integer.toString(astatus.rssi24) + "   "
                        + Integer.toString(bstatus.rssi5) + ","
                        + Integer.toString(bstatus.rssi24));
            }

            if ((astatus.rssi5 > -70) && (bstatus.rssi5 <= WifiConfiguration.INVALID_RSSI)
                    && (astatus.rssi5+boost5+aRssiBoost) > (bstatus.rssi24+bRssiBoost)) {
                    //in this case: a has 5GHz and b doesn't have 5GHz
                    //compare a's 5GHz RSSI to b's 5GHz RSSI

                    //a is seen on 5GHz with good RSSI, greater rssi than b
                    //a is of higher priority - descending
                    order = -10;

                if (VDBG) {
                    logDbg("compareWifiConfigurations linked and prefers " + a.configKey()
                            + " over " + b.configKey()
                            + " due to 5GHz RSSI " + Integer.toString(astatus.rssi5)
                            + " over: 5=" + Integer.toString(bstatus.rssi5)
                            + ", 2.4=" + Integer.toString(bstatus.rssi5));
                }
            } else if ((bstatus.rssi5 > -70) && (astatus.rssi5 <= WifiConfiguration.INVALID_RSSI)
                    && ((bstatus.rssi5+boost5+bRssiBoost) > (astatus.rssi24+aRssiBoost))) {
                    //in this case: b has 5GHz and a doesn't have 5GHz

                    //b is seen on 5GHz with good RSSI, greater rssi than a
                    //a is of lower priority - ascending
                if (VDBG)   {
                    logDbg("compareWifiConfigurations linked and prefers " + b.configKey()
                            + " over " + a.configKey() + " due to 5GHz RSSI "
                            + Integer.toString(astatus.rssi5) + " over: 5="
                            + Integer.toString(bstatus.rssi5) + ", 2.4="
                            + Integer.toString(bstatus.rssi5));
                }
                order = 10;
            } else {
                //TODO: handle cases where configurations are dual band
            }
        }

        //compare by user's choice.
        if (hasConnectChoice(a, b)) {
            //a is of higher priority - descending
            order = order -2;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers -2 " + a.configKey()
                        + " over " + b.configKey()
                        + " due to user choice order -> " + Integer.toString(order));
            }
        }

        if (hasConnectChoice(b, a)) {
            //a is of lower priority - ascending
            order = order + 2;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers +2 " + b.configKey() + " over "
                        + a.configKey() + " due to user choice order ->" + Integer.toString(order));
            }
        }

        //TODO count the number of association rejection
        // and use this to adjust the order by more than +/- 3
        if ((a.status == WifiConfiguration.Status.DISABLED)
                && (a.disableReason == WifiConfiguration.DISABLED_ASSOCIATION_REJECT)) {
            //a is of lower priority - ascending
            //lower the comparison score a bit
            order = order +3;
        }
        if ((b.status == WifiConfiguration.Status.DISABLED)
                && (b.disableReason == WifiConfiguration.DISABLED_ASSOCIATION_REJECT)) {
            //a is of higher priority - descending
            //lower the comparison score a bit
            order = order -3;
        }

        if ((lastSelectedConfiguration != null)
                && a.configKey().equals(lastSelectedConfiguration)) {
            // a is the last selected configuration, so keep it above connect choices
            //by giving a -4 (whereas connect choice preference gives +2)
            order = order - 4;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers -4 " + a.configKey()
                        + " over " + b.configKey() + " because a is the last selected -> "
                        + Integer.toString(order));
            }
        } else if ((lastSelectedConfiguration != null)
                && b.configKey().equals(lastSelectedConfiguration)) {
            // b is the last selected configuration, so keep it above connect choices
            //by giving a +4 (whereas connect choice preference gives -2)
            order = order + 4;
            if (VDBG)   {
                logDbg("compareWifiConfigurations prefers +4 " + a.configKey()
                        + " over " + b.configKey() + " because b is the last selected -> "
                        + Integer.toString(order));
            }
        }

        if (order == 0) {
            //we don't know anything - pick the last seen i.e. K behavior
            //we should do this only for recently picked configurations
            if (a.priority > b.priority) {
                //a is of higher priority - descending
                if (VDBG)   {
                    logDbg("compareWifiConfigurations prefers -1 " + a.configKey() + " over "
                            + b.configKey() + " due to priority");
                }

                order = -1;
            } else if (a.priority < b.priority) {
                //a is of lower priority - ascending
                if (VDBG)  {
                    logDbg("compareWifiConfigurations prefers +1 " + b.configKey() + " over "
                            + a.configKey() + " due to priority");
                }

              order = 1;
            } else {
                //maybe just look at RSSI or band
                if (VDBG)  {
                    logDbg("compareWifiConfigurations prefers +1 " + b.configKey() + " over "
                            + a.configKey() + " due to nothing");
                }

                order = compareWifiConfigurationsRSSI(a, b); //compare RSSI
            }
        }

        String sorder = " == ";
        if (order > 0)
            sorder = " < ";
        if (order < 0)
            sorder = " > ";

        if (VDBG)   {
            logDbg("compareWifiConfigurations Done: " + a.configKey() + sorder
                    + b.configKey() + " order " + Integer.toString(order));
        }

        return order;
    }

    /* attemptAutoJoin function implement the core of the a network switching algorithm */
    void attemptAutoJoin() {
        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();

        //reset the currentConfiguration Key, and set it only if WifiStateMachine and
        // supplicant agree
        mCurrentConfigurationKey = null;
        WifiConfiguration currentConfiguration = mWifiStateMachine.getCurrentWifiConfiguration();

        WifiConfiguration candidate = null;

        /* obtain the subset of recently seen networks */
        List<WifiConfiguration> list = mWifiConfigStore.getRecentConfiguredNetworks(3000, true);
        if (list == null) {
            if (VDBG)  logDbg("attemptAutoJoin nothing");
            return;
        }

        /* find the currently connected network: ask the supplicant directly */
        String val = mWifiNative.status();
        String status[] = val.split("\\r?\\n");
        if (VDBG) {
            logDbg("attemptAutoJoin() status=" + val + " split="
                    + Integer.toString(status.length));
        }

        int currentNetId = -1;
        for (String key : status) {
            if (key.regionMatches(0, "id=", 0, 3)) {
                int idx = 3;
                currentNetId = 0;
                while (idx < key.length()) {
                    char c = key.charAt(idx);

                    if ((c >= 0x30) && (c <= 0x39)) {
                        currentNetId *= 10;
                        currentNetId += c - 0x30;
                        idx++;
                    } else {
                        break;
                    }
                }
            }
        }
        if (DBG) {
            logDbg("attemptAutoJoin() num recent config " + Integer.toString(list.size())
                    + " ---> currentId=" + Integer.toString(currentNetId));
        }

        if (currentConfiguration != null) {
            if (currentNetId != currentConfiguration.networkId) {
                logDbg("attemptAutoJoin() ERROR wpa_supplicant out of sync nid="
                        + Integer.toString(currentNetId) + " WifiStateMachine="
                        + Integer.toString(currentConfiguration.networkId));
                //I think this can happen due do race conditions, now what to do??
                // -> throw an exception, or,
                // -> dont use the current configuration at all for autojoin
                //and hope that autojoining will kick us out of this state.
                currentConfiguration = null;
            } else {
                mCurrentConfigurationKey = currentConfiguration.configKey();
            }
        }

        /* select Best Network candidate from known WifiConfigurations */
        for (WifiConfiguration config : list) {
            if ((config.status == WifiConfiguration.Status.DISABLED)
                    && (config.disableReason == WifiConfiguration.DISABLED_AUTH_FAILURE)) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to auth failure key "
                            + config.configKey(true));
                }
                continue;
            }

            if (config.SSID == null) {
                return;
            }

            if (config.autoJoinStatus >= WifiConfiguration.AUTO_JOIN_TEMPORARY_DISABLED) {
                //avoid temporarily disabled networks altogether
                //TODO: implement a better logic which will reenable the network after some time
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to auto join status "
                            + Integer.toString(config.autoJoinStatus) + " key "
                            + config.configKey(true));
                }
                continue;
            }

            if (config.networkId == currentNetId) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip current candidate  "
                            + Integer.toString(currentNetId)
                            + " key " + config.configKey(true));
                }
                continue;
            }

            if (lastSelectedConfiguration == null ||
                    !config.configKey().equals(lastSelectedConfiguration)) {
                //don't try to autojoin a network that is too far
                if (config.visibility == null) {
                    continue;
                }
                if (config.visibility.rssi5 < -70 && config.visibility.rssi24 < -80) {
                    continue;
                }
            }

            if (DBG) {
                logDbg("attemptAutoJoin trying candidate id=" + config.networkId + " "
                        + config.SSID + " key " + config.configKey(true));
            }

            if (candidate == null) {
                candidate = config;
            } else {
                if (VDBG)  {
                    logDbg("attemptAutoJoin will compare candidate  " + candidate.configKey()
                            + " with " + config.configKey() + " key " + config.configKey(true));
                }

                int order = compareWifiConfigurations(candidate, config);
                if (order > 0) {
                    //ascending : candidate < config
                    candidate = config;
                }
            }
        }

        /* now, go thru scan result to try finding a better Herrevad network */
        if (mNetworkScoreCache != null) {
            int rssi5 = WifiConfiguration.INVALID_RSSI;
            int rssi24 = WifiConfiguration.INVALID_RSSI;
            WifiConfiguration.Visibility visibility;
            if (candidate != null) {
                rssi5 = candidate.visibility.rssi5;
                rssi24 = candidate.visibility.rssi24;
            }

            //get current date
            Date now = new Date();
            long now_ms = now.getTime();

            if (rssi5 < -60 && rssi24 < -70) {
                for (ScanResult result : scanResultCache.values()) {
                    if ((now_ms - result.seen) < 3000) {
                        int score = mNetworkScoreCache.getNetworkScore(result);
                        if (score > 0) {
                            // try any arbitrary formula for now, adding apple and oranges,
                            // i.e. adding network score and "dBm over noise"
                           if (result.frequency < 4000) {
                                if ((result.level + score) > (rssi24 -40)) {
                                    // force it as open, TBD should we otherwise verify that this
                                    // BSSID only supports open??
                                    result.capabilities = "";

                                    //switch to this scan result
                                    candidate =
                                           mWifiConfigStore.wifiConfigurationFromScanResult(result);
                                    candidate.ephemeral = true;
                                }
                           } else {
                                if ((result.level + score) > (rssi5 -30)) {
                                    // force it as open, TBD should we otherwise verify that this
                                    // BSSID only supports open??
                                    result.capabilities = "";

                                    //switch to this scan result
                                    candidate =
                                           mWifiConfigStore.wifiConfigurationFromScanResult(result);
                                    candidate.ephemeral = true;
                                }
                           }
                        }
                    }
                }
            }
        }
        if (candidate != null) {
        /* if candidate is found, check the state of the connection so as
        to decide if we should be acting on this candidate and switching over */
            int networkDelta = compareNetwork(candidate);
            if (DBG && (networkDelta > 0)) {
                logDbg("attemptAutoJoin did find candidate " + candidate.configKey()
                        + " for delta " + Integer.toString(networkDelta));
            }
            /* ASK traffic poller permission to switch:
                for instance,
                if user is currently streaming voice traffic,
                then don’t switch regardless of the delta */

            if (mWifiTrafficPoller.shouldSwitchNetwork(networkDelta)) {
                if (mStaStaSupported) {
                    logDbg("mStaStaSupported --> error do nothing now ");
                } else {
                    if (DBG) {
                        logDbg("AutoJoin auto connect with netId "
                                + Integer.toString(candidate.networkId)
                                + " to " + candidate.configKey());
                    }

                    mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_CONNECT,
                            candidate.networkId);
                    //mWifiConfigStore.enableNetworkWithoutBroadcast(candidate.networkId, true);

                    //we would do the below only if we want to persist the new choice
                    //mWifiConfigStore.selectNetwork(candidate.networkId);

                }
            }
        }
        if (VDBG) logDbg("Done attemptAutoJoin");
    }
}

