package com.github.axet.hourlyreminder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import com.github.axet.hourlyreminder.R;
import com.github.axet.hourlyreminder.basics.Alarm;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class Sound {
    public static final String TAG = Sound.class.getSimpleName();

    public final static Uri DEFAULT_ALARM = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

    // beep ms
    public static final int BEEP = 100;

    public static final int DELAYED_DELAY = 5000;

    Context context;
    TextToSpeech tts;
    ToneGenerator tone;
    MediaPlayer player;
    AudioTrack track;
    Runnable delayed; // tts may not be initalized, on init done, run delayed.run()
    boolean restart; // restart tts once if failed. on apk upgrade tts failed connection.
    Handler handler;
    Set<Runnable> done = new HashSet<>();

    Float volume;
    Runnable increaseVolume;

    public enum Silenced {
        NONE,
        // vibrate instead of sound
        VIBRATE,
        SETTINGS,
        CALL,
        MUSIC
    }

    // AudioSystem.STREAM_ALARM AudioManager.STREAM_ALARM;
    final static int SOUND_CHANNEL = AudioAttributes.USAGE_ALARM;
    final static int SOUND_TYPE = AudioAttributes.CONTENT_TYPE_SONIFICATION;

    public Sound(Context context) {
        this.context = context;

        handler = new Handler();

        ttsCreate();
    }

    void ttsCreate() {
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.ERROR) {
                    tts.setLanguage(Locale.US);

                    if (Build.VERSION.SDK_INT >= 21) {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(SOUND_CHANNEL)
                                .setContentType(SOUND_TYPE)
                                .build());
                    }

                    if (delayed != null) {
                        delayed.run();
                    }
                }
            }
        });
    }

    public void close() {
        playerClose();

        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (track != null) {
            track.release();
            track = null;
        }
        if (delayed != null) {
            handler.removeCallbacks(delayed);
            delayed = null;
        }
    }

    // https://gist.github.com/slightfoot/6330866
    private AudioTrack generateTone(double freqHz, int durationMs) {
        int sampleRate = 44100;
        int count = sampleRate * durationMs / 1000;
        int end = count;
        int stereo = count * 2;
        short[] samples = new short[stereo];
        for (int i = 0; i < stereo; i += 2) {
            short sample = (short) (Math.sin(2 * Math.PI * i / (sampleRate / freqHz)) * 0x7FFF);
            samples[i + 0] = sample;
            samples[i + 1] = sample;
        }
        // old phones bug.
        // http://stackoverflow.com/questions/27602492
        //
        // with MODE_STATIC setNotificationMarkerPosition not called
        AudioTrack track = new AudioTrack(SOUND_CHANNEL, sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                stereo * (Short.SIZE / 8), AudioTrack.MODE_STREAM);
        track.write(samples, 0, stereo);
        if (track.setNotificationMarkerPosition(end) != AudioTrack.SUCCESS)
            throw new RuntimeException("unable to set marker");
        return track;
    }

    public Silenced silencedReminder() {
        Silenced ss = silenced();

        if (ss != Silenced.NONE)
            return ss;

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        boolean v = shared.getBoolean(HourlyApplication.PREFERENCE_VIBRATE, false);
        boolean c = !shared.getString(HourlyApplication.PREFERENCE_CUSTOM_SOUND, "").equals(HourlyApplication.PREFERENCE_CUSTOM_SOUND_OFF);
        boolean s = shared.getBoolean(HourlyApplication.PREFERENCE_SPEAK, false);
        boolean b = shared.getBoolean(HourlyApplication.PREFERENCE_BEEP, false);

        if (!v && !c && !s && !b)
            return Silenced.SETTINGS;

        if (v && !c && !s && !b)
            return Silenced.VIBRATE;

        return Silenced.NONE;
    }

    public Silenced silencedAlarm(Alarm a) {
        Silenced ss = silenced();

        if (ss != Silenced.NONE)
            return ss;

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        boolean v = shared.getBoolean(HourlyApplication.PREFERENCE_VIBRATE, false);
        boolean c = a.ringtone;
        boolean s = a.speech;
        boolean b = a.beep;

        if (!v && !c && !s && !b)
            return Silenced.SETTINGS;

        if (v && !c && !s && !b)
            return Silenced.VIBRATE;

        return Silenced.NONE;
    }

    public Silenced silenced() {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        if (shared.getBoolean(HourlyApplication.PREFERENCE_CALLSILENCE, false)) {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                return Silenced.CALL;
            }
        }

        if (shared.getBoolean(HourlyApplication.PREFERENCE_MUSICSILENCE, false)) {
            AudioManager tm = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (tm.isMusicActive()) {
                return Silenced.MUSIC;
            }
        }

        return Silenced.NONE;
    }

    public void soundReminder(final long time) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        Silenced s = silencedReminder();

        // do we have slince alarm?
        if (s != Silenced.NONE) {
            if (s == Silenced.VIBRATE)
                vibrate();

            String text = "";
            switch (s) {
                case VIBRATE:
                    text += context.getString(R.string.SoundSilencedVibrate);
                    break;
                case CALL:
                    text += context.getString(R.string.SoundSilencedCall);
                    break;
                case MUSIC:
                    text += context.getString(R.string.SoundSilencedMusic);
                    break;
                case SETTINGS:
                    text += context.getString(R.string.SoundSilencedSettings);
                    break;
            }
            text += "\n";
            text += context.getResources().getString(R.string.TimeIs, Alarm.format(context, time));

            Toast t = Toast.makeText(context, text, Toast.LENGTH_SHORT);
            TextView v = (TextView) t.getView().findViewById(android.R.id.message);
            if (v != null)
                v.setGravity(Gravity.CENTER);
            t.show();
            return;
        }

        if (shared.getBoolean(HourlyApplication.PREFERENCE_VIBRATE, false)) {
            vibrate();
        }

        final Runnable custom = new Runnable() {
            @Override
            public void run() {
                if (!shared.getString(HourlyApplication.PREFERENCE_CUSTOM_SOUND, "").equals(HourlyApplication.PREFERENCE_CUSTOM_SOUND_OFF)) {
                    playCustom(null);
                }
            }
        };

        final Runnable speech = new Runnable() {
            @Override
            public void run() {
                if (shared.getBoolean(HourlyApplication.PREFERENCE_SPEAK, false)) {
                    playSpeech(time, custom);
                } else {
                    custom.run();
                }
            }
        };

        final Runnable beep = new Runnable() {
            @Override
            public void run() {
                if (shared.getBoolean(HourlyApplication.PREFERENCE_BEEP, false)) {
                    playBeep(speech);
                } else {
                    speech.run();
                }
            }
        };

        timeToast(time);

        beep.run();
    }

    public void playCustom(final Runnable done) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        String custom = shared.getString(HourlyApplication.PREFERENCE_CUSTOM_SOUND, "");

        if (custom.equals("ringtone")) {
            String uri = shared.getString(HourlyApplication.PREFERENCE_RINGTONE, "");
            playerClose();

            Sound.this.done.clear();
            Sound.this.done.add(done);

            if (uri.isEmpty()) {
                if (done != null)
                    done.run();
            } else {
                player = playOnce(Uri.parse(uri), new Runnable() {
                    @Override
                    public void run() {
                        if (done != null && Sound.this.done.contains(done))
                            done.run();
                        playerClose();
                    }
                });
            }
        } else if (custom.equals("sound")) {
            String uri = shared.getString(HourlyApplication.PREFERENCE_SOUND, "");
            playerClose();

            Sound.this.done.clear();
            Sound.this.done.add(done);

            if (uri.isEmpty()) {
                if (done != null)
                    done.run();
            } else {
                player = playOnce(Uri.parse(uri), new Runnable() {
                    @Override
                    public void run() {
                        if (done != null && Sound.this.done.contains(done))
                            done.run();
                        playerClose();
                    }
                });
            }
        } else {
            if (done != null)
                done.run();
        }
    }

    public void playBeep(final Runnable done) {
        if (track != null)
            track.release();

        track = generateTone(900, BEEP);

        if (Build.VERSION.SDK_INT < 21) {
            track.setStereoVolume(getVolume(), getVolume());
        } else {
            track.setVolume(getVolume());
        }

        Sound.this.done.clear();
        Sound.this.done.add(done);

        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack t) {
                // prevent strange android bug, with second beep when connecting android to external usb audio source.
                // seems like this beep pushed to external audio source from sound cache.
                if (track != null) {
                    track.release();
                    track = null;
                }
                if (done != null && Sound.this.done.contains(done))
                    done.run();
            }

            @Override
            public void onPeriodicNotification(AudioTrack track) {
            }
        });

        track.play();
    }

    public void playRingtone(Uri uri) {
        playerClose();
        player = MediaPlayer.create(context, uri);
        if (player == null) {
            player = MediaPlayer.create(context, Alarm.DEFAULT_RING);
        }
        if (player == null) {
            if (tone != null) {
                tone.release();
            }
            tone = new ToneGenerator(SOUND_CHANNEL, 100);
            tone.startTone(ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_NORMAL);
            return;
        }
        if (Build.VERSION.SDK_INT >= 21) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(SOUND_CHANNEL)
                    .setContentType(SOUND_TYPE)
                    .build());
        }
        player.setLooping(true);

        startPlayer(player);
    }

    public void startPlayer(final MediaPlayer player) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        final int inc = Integer.parseInt(shared.getString(HourlyApplication.PREFERENCE_INCREASE_VOLUME, "0")) * 1000;

        if (inc == 0) {
            player.setVolume(getVolume(), getVolume());
            player.start();

            return;
        }

        final float startVolume;

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        float systemVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM) / (float) am.getStreamVolume(AudioManager.STREAM_ALARM);
        float alarmVolume = getVolume();

        // if user trying to reduce alarms volume, then use it as start volume. else start from silence
        if (systemVolume > alarmVolume)
            startVolume = alarmVolume;
        else
            startVolume = 0;

        if (increaseVolume != null)
            handler.removeCallbacks(increaseVolume);

        increaseVolume = new Runnable() {
            int step = 0;
            int steps = 50;
            int delay = 100;
            // we start from startVolume, rest - how much we should increase
            float rest = 0;

            {
                steps = (inc / delay);
                rest = 1f - startVolume;
            }

            @Override
            public void run() {
                if (player == null)
                    return;

                float log1 = (float) (Math.log(steps - step) / Math.log(steps));
                // volume 0..1
                float vol = 1 - log1;

                // actual volume
                float restvol = startVolume + rest * vol;

                try {
                    player.setVolume(restvol, restvol);
                } catch (IllegalStateException e) {
                    // ignore. player probably already closed
                    return;
                }

                step++;

                if (step >= steps) {
                    // should be clear anyway
                    handler.removeCallbacks(increaseVolume);
                    increaseVolume = null;
                    Log.d(TAG, "increaseVolume done");
                    return;
                }

                handler.postDelayed(increaseVolume, delay);
            }
        };

        increaseVolume.run();
        player.start();
    }

    float getRingtoneVolume() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (Integer.parseInt(shared.getString(HourlyApplication.PREFERENCE_INCREASE_VOLUME, "0")) > 0) {
            return 0;
        }

        return getVolume();
    }

    float getVolume() {
        if (volume != null)
            return volume;

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        return (float) (Math.pow(shared.getFloat(HourlyApplication.PREFERENCE_VOLUME, 1f), 3));
    }

    public void setVolume(float f) {
        volume = f;
    }

    public void playSpeech(final long time, final Runnable done) {
        Sound.this.done.clear();
        Sound.this.done.add(done);

        // clear delayed(), sound just played
        final Runnable clear = new Runnable() {
            @Override
            public void run() {
                if (delayed != null) {
                    handler.removeCallbacks(delayed);
                    delayed = null;
                }
                if (done != null && Sound.this.done.contains(done))
                    done.run();
            }
        };

        if (tts == null) {
            ttsCreate();
        }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                clear.run();
            }

            @Override
            public void onError(String utteranceId) {
                clear.run();
            }
        });

        // TTS may say failed, but play sounds successfuly. we need regardless or failed do not
        // play speech twice if clear.run() was called.
        if (!playSpeech(time)) {
            Toast.makeText(context, context.getString(R.string.WaitTTS), Toast.LENGTH_SHORT).show();
            if (delayed != null) {
                handler.removeCallbacks(delayed);
            }
            delayed = new Runnable() {
                @Override
                public void run() {
                    if (!playSpeech(time)) {
                        tts.shutdown(); // on apk upgrade tts failed always. close it and restart.
                        tts = null;
                        if (restart) {
                            Toast.makeText(context, context.getString(R.string.FailedTTS), Toast.LENGTH_SHORT).show();
                            clear.run();
                        } else {
                            restart = true;
                            Toast.makeText(context, context.getString(R.string.FailedTTSRestar), Toast.LENGTH_SHORT).show();
                            if (delayed != null) {
                                handler.removeCallbacks(delayed);
                            }
                            delayed = new Runnable() {
                                @Override
                                public void run() {
                                    playSpeech(time, done);
                                }
                            };
                            handler.postDelayed(delayed, DELAYED_DELAY);
                        }
                    }
                }
            };
            handler.postDelayed(delayed, DELAYED_DELAY);
        }
    }

    boolean playSpeech(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int h = DateFormat.is24HourFormat(context) ? hour : c.get(Calendar.HOUR);
        int min = c.get(Calendar.MINUTE);

        String speak = "";

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        boolean speakAMPMFlag = !DateFormat.is24HourFormat(context) && shared.getBoolean(HourlyApplication.PREFERENCE_SPEAK_AMPM, false);

        String lang = shared.getString(HourlyApplication.PREFERENCE_LANGUAGE, "");

        Locale locale;

        if (lang.isEmpty())
            locale = Locale.getDefault();
        else
            locale = new Locale(lang);

        if (tts.isLanguageAvailable(locale) == TextToSpeech.LANG_NOT_SUPPORTED) {
            locale = new Locale("en_US");
        }

        String speakAMPM = "";
        String speakHour = "";
        String speakMinute = "";

        // Russian requires dots "." and hours/minutes
        Locale ru = new Locale("ru");
        if (locale.toString().startsWith(ru.toString())) {
            if (speakAMPMFlag) {
                speakAMPM = HourlyApplication.getHourString(context, ru, hour);
            }

            speakHour = HourlyApplication.getQuantityString(context, ru, R.plurals.hours, h);
            speakMinute = HourlyApplication.getQuantityString(context, ru, R.plurals.minutes, min);

            if (min != 0) {
                speak = HourlyApplication.getString(context, ru, R.string.speak_time, ". " + speakHour + ". " + speakMinute + " " + speakAMPM);
            } else {
                if (speakAMPMFlag)
                    speak = HourlyApplication.getString(context, ru, R.string.speak_time, ". " + speakHour + ". " + speakAMPM);
                else
                    speak = HourlyApplication.getString(context, ru, R.string.speak_time_24, speakHour);
            }
            tts.setLanguage(ru);
        }

        // english requres zero minutes
        Locale en = new Locale("en");
        if (locale.toString().startsWith(en.toString()) || speak.isEmpty()) {
            if (speakAMPMFlag) {
                speakAMPM = HourlyApplication.getHourString(context, en, hour);
            }

            speakHour = String.format("%d", h);

            if (min < 10)
                speakMinute = String.format("o %d", min);
            else
                speakMinute = String.format("%d", min);

            if (min != 0) {
                speak = HourlyApplication.getString(context, en, R.string.speak_time, speakHour + " " + speakMinute + " " + speakAMPM);
            } else {
                if (speakAMPMFlag)
                    speak = HourlyApplication.getString(context, en, R.string.speak_time, speakHour + " " + speakAMPM);
                else
                    speak = HourlyApplication.getString(context, en, R.string.speak_time_24, speakHour);
            }
            tts.setLanguage(en);
        }

        Log.d(TAG, speak);

        if (Build.VERSION.SDK_INT >= 21) {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, getVolume());
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "DONE");
            if (tts.speak(speak, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString()) != TextToSpeech.SUCCESS) {
                return false;
            }
        } else {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, Float.toString(getVolume()));
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "DONE");
            if (tts.speak(speak, TextToSpeech.QUEUE_FLUSH, params) != TextToSpeech.SUCCESS) {
                return false;
            }
        }
        restart = false;
        return true;
    }

    public void timeToast(long time) {
        String text = context.getResources().getString(R.string.TimeIs, Alarm.format(context, time));
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public void silencedToast(Silenced s) {
        String text = "";
        switch (s) {
            case CALL:
                text += context.getString(R.string.SoundSilencedCall);
                break;
            case MUSIC:
                text += context.getString(R.string.SoundSilencedMusic);
                break;
            case SETTINGS:
                text += context.getString(R.string.SoundSilencedSettings);
                break;
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public MediaPlayer playOnce(Uri uri, final Runnable done) {
        MediaPlayer player = MediaPlayer.create(context, uri);
        if (player == null) {
            player = MediaPlayer.create(context, DEFAULT_ALARM);
        }
        if (player == null) {
            Toast.makeText(context, context.getString(R.string.NoDefaultRingtone), Toast.LENGTH_SHORT).show();
            if (done != null)
                done.run();
            return null;
        }
        if (Build.VERSION.SDK_INT >= 21) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(SOUND_CHANNEL)
                    .setContentType(SOUND_TYPE)
                    .build());
        }

        // https://code.google.com/p/android/issues/detail?id=1314
        player.setLooping(false);

        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                                           @Override
                                           public void onCompletion(MediaPlayer mp) {
                                               if (done != null)
                                                   done.run();
                                           }
                                       }
        );

        startPlayer(player);

        return player;
    }

    public void vibrate() {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(400);
    }

    public void vibrateStart() {
        long[] pattern = {0, 1000, 300};
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(pattern, 0);
    }

    public void vibrateStop() {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        v.cancel();
    }

    public boolean playerClose() {
        done.clear();

        if (increaseVolume != null) {
            handler.removeCallbacks(increaseVolume);
            increaseVolume = null;
        }

        if (player != null) {
            player.release();
            player = null;
            return true;
        }

        return false;
    }

    public Silenced playAlarm(final Alarm a) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);

        Silenced s = silencedAlarm(a);

        if (s == Silenced.VIBRATE) {
            vibrateStart();
            return s;
        }

        if (s != Silenced.NONE)
            return s;

        if (shared.getBoolean(HourlyApplication.PREFERENCE_VIBRATE, false)) {
            vibrateStart();
        }

        final long time = System.currentTimeMillis();

        if (a.beep) {
            playBeep(new Runnable() {
                         @Override
                         public void run() {
                             if (a.speech) {
                                 playSpeech(time, new Runnable() {
                                     @Override
                                     public void run() {
                                         if (a.ringtone) {
                                             playRingtone(Uri.parse(a.ringtoneValue));
                                         }
                                     }
                                 });
                             } else if (a.ringtone) {
                                 playRingtone(Uri.parse(a.ringtoneValue));
                             }
                         }
                     }
            );
        } else if (a.speech) {
            playSpeech(time, new Runnable() {
                @Override
                public void run() {
                    playRingtone(Uri.parse(a.ringtoneValue));
                }
            });
        } else if (a.ringtone) {
            playRingtone(Uri.parse(a.ringtoneValue));
        }

        return s;
    }
}
