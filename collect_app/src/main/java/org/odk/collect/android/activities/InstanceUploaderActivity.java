/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.activities;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import org.odk.collect.android.R;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.dao.InstancesDao;
import org.odk.collect.android.fragments.dialogs.SimpleDialog;
import org.odk.collect.android.listeners.InstanceUploaderListener;
import org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns;
import org.odk.collect.android.tasks.InstanceServerUploader;
import org.odk.collect.android.utilities.ApplicationConstants;
import org.odk.collect.android.utilities.ArrayUtils;
import org.odk.collect.android.utilities.AuthDialogUtility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import timber.log.Timber;

/**
 * Activity to upload completed forms.
 *
 * @author Carl Hartung (carlhartung@gmail.com)
 */
public class InstanceUploaderActivity extends CollectAbstractActivity implements InstanceUploaderListener,
        AuthDialogUtility.AuthDialogUtilityResultListener, View.OnClickListener {
    private static final int PROGRESS_DIALOG = 1;
    private static final int AUTH_DIALOG = 2;

    private static final String AUTH_URI = "auth";
    private static final String ALERT_MSG = "alertmsg";
    private static final String TO_SEND = "tosend";

    private ProgressDialog progressDialog;

    private String alertMsg;

    private InstanceServerUploader instanceServerUploader;

    // maintain a list of what we've yet to send, in case we're interrupted by auth requests
    private Long[] instancesToSend;

    // maintain a list of what we've sent, in case we're interrupted by auth requests
    private HashMap<String, String> uploadedInstances;
    private String url;

    ListView detectedDevices;
    Button buttonSearch,buttonOn;
    ArrayAdapter<String> detectedAdapter;
    BluetoothDevice bdDevice;
    BluetoothAdapter bluetoothAdapter = null;
    ArrayList<BluetoothDevice> arrayListBluetoothDevices = null;
    boolean isBluetoothOn;
    private static final String TAG = "MainActivity";
    private final static UUID uuid = UUID.fromString("2cc9ec17-8fd3-4e10-a28a-4be8383a9737");
    String path ;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.connect_bluetooth);

        Timber.i("onCreate: %s", ((savedInstanceState == null) ? "creating" : "re-initializing"));

        alertMsg = getString(R.string.please_wait);

        uploadedInstances = new HashMap<String, String>();

        setTitle(getString(R.string.send_data));

        // get any simple saved state...
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(ALERT_MSG)) {
                alertMsg = savedInstanceState.getString(ALERT_MSG);
            }

            url = savedInstanceState.getString(AUTH_URI);
        }

        // and if we are resuming, use the TO_SEND list of not-yet-sent submissions
        // Otherwise, construct the list from the incoming intent value
        long[] selectedInstanceIDs = null;
        if (savedInstanceState != null && savedInstanceState.containsKey(TO_SEND)) {
            selectedInstanceIDs = savedInstanceState.getLongArray(TO_SEND);
        } else {
            // get instances to upload...
            Intent intent = getIntent();
            selectedInstanceIDs = intent.getLongArrayExtra(FormEntryActivity.KEY_INSTANCES);
        }

        instancesToSend = ArrayUtils.toObject(selectedInstanceIDs);

        // at this point, we don't expect this to be empty...
        if (instancesToSend.length == 0) {
            Timber.e("onCreate: No instances to upload!");
            // drop through -- everything will process through OK
        } else {
            Timber.i("onCreate: Beginning upload of %d instances!", instancesToSend.length);
        }

        // get the task if we've changed orientations. If it's null it's a new upload.
        instanceServerUploader = (InstanceServerUploader) getLastCustomNonConfigurationInstance();
//        if (instanceServerUploader == null) {
//            // setup dialog and upload task
//            showDialog(PROGRESS_DIALOG);
//            instanceServerUploader = new InstanceServerUploader();
//
//            // register this activity with the new uploader task
//            instanceServerUploader.setUploaderListener(this);
//            instanceServerUploader.execute(instancesToSend);
//        }





        detectedDevices = (ListView) findViewById(R.id.detectedList);
        buttonSearch = (Button) findViewById(R.id.search);
        buttonOn = (Button) findViewById(R.id.bluetoothToggle);

        buttonOn.setOnClickListener(this);
        buttonSearch.setOnClickListener(this);


        detectedDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                bdDevice = arrayListBluetoothDevices.get(i);
                Set<BluetoothDevice> pairedDevice = bluetoothAdapter.getBondedDevices();
                if(pairedDevice.size()>0)
                {
                    for(BluetoothDevice device : pairedDevice)
                    {
                        if(device.equals(bdDevice)) {
                            Log.d(TAG,"Already Paired");
                            BluetoothDevice bluetoothDevice = bdDevice;
                            // Initiate a connection request in a separate thread
                            showDialog(PROGRESS_DIALOG);
                            ConnectingThread t = new ConnectingThread(bluetoothDevice);
                            t.start();
                            break;
                        }
                    }
                }
            }
        });

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        arrayListBluetoothDevices = new ArrayList<BluetoothDevice>();
        detectedAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice);
        detectedDevices.setAdapter(detectedAdapter);
        detectedAdapter.notifyDataSetChanged();
        if (!bluetoothAdapter.isEnabled()) {
            buttonOn.setText("Bluetooth On");
        } else {
            buttonOn.setText("Bluetooth Off");
        }






    }

    @Override
    protected void onStart() {
        super.onStart();
        Collect.getInstance().getActivityLogger().logOnStart(this);
    }

    @Override
    protected void onResume() {
        Timber.i("onResume: Resuming upload of %d instances!", instancesToSend.length);
        if (instanceServerUploader != null) {
            instanceServerUploader.setUploaderListener(this);
        }
        super.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ALERT_MSG, alertMsg);
        outState.putString(AUTH_URI, url);
        outState.putLongArray(TO_SEND, ArrayUtils.toPrimitive(instancesToSend));
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return instanceServerUploader;
    }

    @Override
    protected void onStop() {
        Collect.getInstance().getActivityLogger().logOnStop(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (instanceServerUploader != null) {
            instanceServerUploader.setUploaderListener(null);
        }
        super.onDestroy();
    }

    @Override
    public void uploadingComplete(HashMap<String, String> result) {
        Timber.i("uploadingComplete: Processing results (%d) from upload of %d instances!",
                result.size(), instancesToSend.length);

        try {
            dismissDialog(PROGRESS_DIALOG);
        } catch (Exception e) {
            // tried to close a dialog not open. don't care.
        }

        Set<String> keys = result.keySet();
        Iterator<String> it = keys.iterator();

        StringBuilder message = new StringBuilder();
        int count = keys.size();
        while (count > 0) {
            String[] selectionArgs = null;

            if (count > ApplicationConstants.SQLITE_MAX_VARIABLE_NUMBER) {
                selectionArgs = new String[ApplicationConstants.SQLITE_MAX_VARIABLE_NUMBER];
            } else {
                selectionArgs = new String[count];
            }

            StringBuilder selection = new StringBuilder();
            selection.append(InstanceColumns._ID + " IN (");

            int i = 0;
            while (it.hasNext() && i < selectionArgs.length) {
                selectionArgs[i] = it.next();
                selection.append("?");

                if (i != selectionArgs.length - 1) {
                    selection.append(",");
                }
                i++;
            }

            selection.append(")");
            count -= selectionArgs.length;

            StringBuilder queryMessage = new StringBuilder();
            Cursor results = null;
            try {
                results = new InstancesDao().getInstancesCursor(selection.toString(), selectionArgs);
                if (results.getCount() > 0) {
                    results.moveToPosition(-1);
                    while (results.moveToNext()) {
                        String name =
                                results.getString(
                                        results.getColumnIndex(InstanceColumns.DISPLAY_NAME));
                        String id = results.getString(results.getColumnIndex(InstanceColumns._ID));
                        String text = localizeDefaultAggregateSuccessfulText(result.get(id));
                        queryMessage
                                .append(name)
                                .append(" - ")
                                .append(text)
                                .append("\n\n");
                    }
                }
            } catch (SQLException e) {
                Timber.e(e);
            } finally {
                if (results != null) {
                    results.close();
                }
            }
            message.append(queryMessage.toString());
        }
        if (message.length() == 0) {
            message.append(getString(R.string.no_forms_uploaded));
        }

        if (!isInstanceStateSaved()) {
//            createUploadInstancesResultDialog(message.toString().trim());
            createUploadInstancesResultDialog("Form Uploaded");
        } else {
            finish();
        }
    }

    private String localizeDefaultAggregateSuccessfulText(String text) {
        if (text.equals("full submission upload was successful!")) {
            text = getString(R.string.success);
        }
        return text;
    }

    @Override
    public void progressUpdate(int progress, int total) {
        alertMsg = getString(R.string.sending_items, String.valueOf(progress), String.valueOf(total));
        progressDialog.setMessage(alertMsg);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case PROGRESS_DIALOG:
                Collect.getInstance().getActivityLogger().logAction(this,
                        "onCreateDialog.PROGRESS_DIALOG", "show");

                progressDialog = new ProgressDialog(this);
//                DialogInterface.OnClickListener loadingButtonListener =
//                        new DialogInterface.OnClickListener() {
//                            @Override
//                            public void onClick(DialogInterface dialog, int which) {
//                                Collect.getInstance().getActivityLogger().logAction(this,
//                                        "onCreateDialog.PROGRESS_DIALOG", "cancel");
//                                dialog.dismiss();
//                                instanceServerUploader.cancel(true);
//                                instanceServerUploader.setUploaderListener(null);
//                                finish();
//                            }
//                        };
                progressDialog.setTitle(getString(R.string.uploading_data));
                progressDialog.setMessage(alertMsg);
                progressDialog.setIndeterminate(true);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                progressDialog.setCancelable(false);
                //progressDialog.setButton(getString(R.string.cancel), loadingButtonListener);
                return progressDialog;
            case AUTH_DIALOG:
                Timber.i("onCreateDialog(AUTH_DIALOG): for upload of %d instances!",
                        instancesToSend.length);
                Collect.getInstance().getActivityLogger().logAction(this,
                        "onCreateDialog.AUTH_DIALOG", "show");

                return new AuthDialogUtility().createDialog(this, this, this.url);
        }

        return null;
    }

    @Override
    public void authRequest(Uri url, HashMap<String, String> doneSoFar) {
        if (progressDialog.isShowing()) {
            // should always be showing here
            progressDialog.dismiss();
        }

        // add our list of completed uploads to "completed"
        // and remove them from our toSend list.
        ArrayList<Long> workingSet = new ArrayList<Long>();
        Collections.addAll(workingSet, instancesToSend);
        if (doneSoFar != null) {
            Set<String> uploadedInstances = doneSoFar.keySet();

            for (String uploadedInstance : uploadedInstances) {
                Long removeMe = Long.valueOf(uploadedInstance);
                boolean removed = workingSet.remove(removeMe);
                if (removed) {
                    Timber.i("%d was already sent, removing from queue before restarting task",
                            removeMe);
                }
            }
            this.uploadedInstances.putAll(doneSoFar);
        }

        // and reconstruct the pending set of instances to send
        Long[] updatedToSend = new Long[workingSet.size()];
        for (int i = 0; i < workingSet.size(); ++i) {
            updatedToSend[i] = workingSet.get(i);
        }
        instancesToSend = updatedToSend;

        this.url = url.toString();
        showDialog(AUTH_DIALOG);
    }

    private void createUploadInstancesResultDialog(String message) {
        Collect.getInstance().getActivityLogger().logAction(this, "createUploadInstancesResultDialog", "show");

        String dialogTitle = getString(R.string.upload_results);
        int iconID = android.R.drawable.ic_dialog_info;
        String buttonTitle = getString(R.string.ok);

        SimpleDialog simpleDialog = SimpleDialog.newInstance(dialogTitle, iconID, message, buttonTitle, true);
        simpleDialog.show(getSupportFragmentManager(), SimpleDialog.COLLECT_DIALOG_TAG);
    }

    @Override
    public void updatedCredentials() {
//        showDialog(PROGRESS_DIALOG);
//        instanceServerUploader = new InstanceServerUploader();
//
//        // register this activity with the new uploader task
//        instanceServerUploader.setUploaderListener(this);
//        instanceServerUploader.execute(instancesToSend);
    }

    @Override
    public void cancelledUpdatingCredentials() {
        finish();
    }






    private void onBluetooth() {
        if(!bluetoothAdapter.isEnabled()){
            bluetoothAdapter.enable();
        }
    }
    private void offBluetooth() {
        if(bluetoothAdapter.isEnabled()){
            bluetoothAdapter.disable();
        }
    }

    private void startSearch() {
        Log.d(TAG, "Search Started");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);

        registerReceiver(myReceiver, intentFilter);
        bluetoothAdapter.startDiscovery();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.bluetoothToggle:
                if (isBluetoothOn) {
                    offBluetooth();
                    buttonOn.setText("Bluetooth On");
                }
                else {
                    onBluetooth();
                    buttonOn.setText("Bluetooth Off");
                }
                break;
            case R.id.search:
                arrayListBluetoothDevices.clear();
                detectedAdapter.notifyDataSetChanged();
                startSearch();
                break;
        }
    }
    private BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                boolean flag = true;    // flag to indicate that particular device is already in the arlist or not
                for(int i = 0; i<arrayListBluetoothDevices.size();i++)
                {
                    if(device.getAddress().equals(arrayListBluetoothDevices.get(i).getAddress()))
                    {
                        flag = false;
                    }
                }
                if(flag == true)
                {
                    detectedAdapter.add(device.getName()+"\n"+device.getAddress());
                    arrayListBluetoothDevices.add(device);
                    detectedAdapter.notifyDataSetChanged();
                }
            }else if(BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)){

            }else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)){

            }
        }
    };



    private class ConnectingThread extends Thread {
        private final BluetoothSocket bluetoothSocket;
        private final BluetoothDevice bluetoothDevice;

        public ConnectingThread(BluetoothDevice device) {

            BluetoothSocket temp = null;
            bluetoothDevice = device;

            // Get a BluetoothSocket to connect with the given BluetoothDevice
            try {
                temp = bluetoothDevice.createRfcommSocketToServiceRecord(uuid);
            } catch (IOException e) {
                e.printStackTrace();
            }
            bluetoothSocket = temp;
        }

        public void run() {
            // Cancel any discovery as it will slow down the connection
            bluetoothAdapter.cancelDiscovery();

            try {
                // This will block until it succeeds in connecting to the device
                // through the bluetoothSocket or throws an exception
                bluetoothSocket.connect();
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Connection Established",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                if (instanceServerUploader == null) {
                    // setup dialog and upload task
                    instanceServerUploader = new InstanceServerUploader(bluetoothSocket);

                    // register this activity with the new uploader task
                    instanceServerUploader.setUploaderListener(InstanceUploaderActivity.this);
                    instanceServerUploader.execute(instancesToSend);
                }
            } catch (IOException connectException) {
                connectException.printStackTrace();

                try {
                    bluetoothSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            // Code to manage the connection in a separate thread
        /*
            manageBluetoothConnection(bluetoothSocket);
        */
        }

        // Cancel an open connection and terminate the thread
        public void cancel() {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
