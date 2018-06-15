package com.samourai.sentinel.sweep;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.Toast;
import android.util.Log;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.json.JSONException;
import org.json.JSONObject;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;

import org.bitcoinj.params.MainNetParams;

import com.samourai.sentinel.R;
import com.samourai.sentinel.api.APIFactory;
import com.samourai.sentinel.segwit.P2SH_P2WPKH;
import com.samourai.sentinel.segwit.SegwitAddress;

public class SweepUtil  {

    public static int TYPE_P2PKH = 0;
    public static int TYPE_P2SH_P2WPKH = 1;
    public static int TYPE_P2WPKH = 2;

    private static Context context = null;
    private static SweepUtil instance = null;

    private static UTXO utxoP2PKH = null;
    private static UTXO utxoP2SH_P2WPKH = null;
    private static UTXO utxoP2WPKH = null;

    private static String addressP2PKH = null;
    private static String addressP2SH_P2WPKH = null;
    private static String addressP2WPKH = null;

    private SweepUtil() { ; }

    public static SweepUtil getInstance(Context ctx) {

        context = ctx;

        if(instance == null)    {
            instance = new SweepUtil();
        }

        return instance;
    }

    public void sweep(final PrivKeyReader privKeyReader, final String strReceiveAddress, final int type)  {

        new Thread(new Runnable() {
            @Override
            public void run() {

                Looper.prepare();

                try {

                    if(privKeyReader == null || privKeyReader.getKey() == null || !privKeyReader.getKey().hasPrivKey())    {
                        Toast.makeText(context, R.string.cannot_recognize_privkey, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String address = null;
                    UTXO utxo = null;

                    if(type == TYPE_P2SH_P2WPKH)    {
                        utxo = utxoP2SH_P2WPKH;
                        address = addressP2SH_P2WPKH;
                    }
                    else if(type == TYPE_P2WPKH)    {
                        utxo = utxoP2WPKH;
                        address = addressP2WPKH;
                    }
                    else    {
                        addressP2PKH = privKeyReader.getKey().toAddress(MainNetParams.get()).toString();
                        Log.d("SweepUtil", "address derived P2PKH:" + addressP2PKH);
                        addressP2SH_P2WPKH = new P2SH_P2WPKH(privKeyReader.getKey(), MainNetParams.get()).getAddressAsString();
                        Log.d("SweepUtil", "address derived P2SH_P2WPKH:" + addressP2SH_P2WPKH);
                        addressP2WPKH = new SegwitAddress(privKeyReader.getKey(), MainNetParams.get()).getBech32AsString();
                        Log.d("SweepUtil", "address derived P2WPKH:" + addressP2WPKH);

                        utxoP2PKH = APIFactory.getInstance(context).getUnspentOutputsForSweep(addressP2PKH);
                        utxoP2SH_P2WPKH = APIFactory.getInstance(context).getUnspentOutputsForSweep(addressP2SH_P2WPKH);
                        utxoP2WPKH = APIFactory.getInstance(context).getUnspentOutputsForSweep(addressP2WPKH);

                        utxo = utxoP2PKH;
                        address = addressP2PKH;
                    }

                    if(utxo != null && utxo.getOutpoints().size() > 0)    {

                        long total_value = 0L;
                        final List<MyTransactionOutPoint> outpoints = utxo.getOutpoints();
                        for(MyTransactionOutPoint outpoint : outpoints)   {
                            total_value += outpoint.getValue().longValue();
                        }

                        FeeUtil.getInstance().setSuggestedFee(FeeUtil.getInstance().getNormalFee());
                        if(FeeUtil.getInstance().getSuggestedFee().getDefaultPerKB().longValue() <= 1000L)    {
                            SuggestedFee suggestedFee = new SuggestedFee();
                            suggestedFee.setDefaultPerKB(BigInteger.valueOf(1100L));
                            Log.d("SendActivity", "adjusted fee:" + suggestedFee.getDefaultPerKB().longValue());
                            FeeUtil.getInstance().setSuggestedFee(suggestedFee);
                        }
                        final BigInteger fee;
                        if(type == TYPE_P2SH_P2WPKH)    {
                            fee = FeeUtil.getInstance().estimatedFeeSegwit(0, outpoints.size(), 1);
                        }
                        else if(type == TYPE_P2WPKH)    {
                            fee = FeeUtil.getInstance().estimatedFeeSegwit(0, 0, outpoints.size(), 1);
                        }
                        else    {
                            fee = FeeUtil.getInstance().estimatedFee(outpoints.size(), 1);
                        }

                        final long amount = total_value - fee.longValue();
                        Log.d("SweepUtil", "Total value:" + total_value);
                        Log.d("SweepUtil", "Amount:" + amount);
                        Log.d("SweepUtil", "Fee:" + fee.toString());
                        Log.d("SweepUtil", "Receive address:" + strReceiveAddress);

                        String message = "Sweep " + Coin.valueOf(amount).toPlainString() + " from " + address + " (fee:" + Coin.valueOf(fee.longValue()).toPlainString() + ")?";

                        new AlertDialog.Builder(context)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {

                                Log.d("SweepUtil", "start sweep");

                                final HashMap<String, BigInteger> receivers = new HashMap<String, BigInteger>();
                                receivers.put(strReceiveAddress, BigInteger.valueOf(amount));
                                Transaction tx = SendFactory.getInstance(context).makeTransaction(0, outpoints, receivers);

                                Log.d("SweepUtil", "tx is " + ((tx == null) ? "null" : "not null"));

                                tx = SendFactory.getInstance(context).signTransactionForSweep(tx, privKeyReader);
                                final String hexTx = new String(Hex.encode(tx.bitcoinSerialize()));
                                Log.d("SweepUtil", hexTx);
//
                                String response = null;
                                try {
                                    response = PushTx.getInstance(context).samourai(hexTx);

                                    if(response != null)    {
                                        JSONObject jsonObject = new org.json.JSONObject(response);
                                        if(jsonObject.has("status"))    {
                                            if(jsonObject.getString("status").equals("ok"))    {
                                                Toast.makeText(context, R.string.tx_ok, Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    }
                                    else    {
                                        Toast.makeText(context, R.string.pushtx_returns_null, Toast.LENGTH_SHORT).show();
                                    }
                                }
                                catch(JSONException je) {
                                    Toast.makeText(context, "pushTx:" + je.getMessage(), Toast.LENGTH_SHORT).show();
                                }

                                dialog.dismiss();

                            }
                        })
                        .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {
                                dialog.dismiss();
                            }
                        }).show();

                    }
                    else if(type == TYPE_P2SH_P2WPKH)    {
                        sweep(privKeyReader, strReceiveAddress, TYPE_P2WPKH);
                    }
                    else if(type == TYPE_P2PKH)    {
                        sweep(privKeyReader, strReceiveAddress, TYPE_P2SH_P2WPKH);
                    }
                    else if(type == TYPE_P2WPKH)    {
                        ;
                    }

                }
                catch(Exception e) {
                    Log.d("SweepUtil", e.getMessage());
                    Toast.makeText(context, context.getText(R.string.cannot_sweep_privkey) + ", " + e.getMessage() , Toast.LENGTH_SHORT).show();
                }

                Looper.loop();

            }
        }).start();

    }

}
