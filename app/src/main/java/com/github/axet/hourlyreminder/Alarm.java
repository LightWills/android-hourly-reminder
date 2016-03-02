package com.github.axet.hourlyreminder;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class Alarm {
    public final static String DEFAULT_RING = "content://settings/system/ringtone";

    // keep EVERYDAY order
    int[] DAYS = new int[]{R.string.MONDAY, R.string.TUESDAY, R.string.WEDNESDAY,
            R.string.THURSDAY, R.string.FRIDAY, R.string.SATURDAY, R.string.SUNDAY};

    Integer[] EVERYDAY = new Integer[]{Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};

    Integer[] WEEKDAY = new Integer[]{Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY};

    Integer[] WEEKEND = new Integer[]{Calendar.SATURDAY, Calendar.SUNDAY};

    protected Context context;

    // time when alarm start to be active. used to snooze upcoming today alarms.
    //
    // may point in past or future. if it points to the past - it is currently active.
    // if it points to tomorrow or more days - do not send it to Alarm Manager until it is active.
    //
    // holds current hour and minute as part of active time.
    public long time;
    public boolean enable;
    public boolean weekdays;
    public List<Integer> weekdaysValues;
    public boolean ringtone;
    public String ringtoneValue;
    public boolean beep;
    public boolean speech;

    public Alarm(Context context) {
        this.context = context;

        setTime(9, 0);

        enable = false;
        weekdays = true;
        weekdaysValues = new ArrayList<Integer>(Arrays.asList(EVERYDAY));
        ringtone = false;
        beep = false;
        speech = true;
        ringtoneValue = DEFAULT_RING;
    }

    public Alarm(Context context, long time) {
        this(context);

        Calendar c = Calendar.getInstance();
        c.setTime(new Date(time));
        this.time = c.getTimeInMillis();

        this.enable = true;
    }

    // keep proper order week days. should take values from settings.
    public List<Integer> order(List<Integer> list) {
        ArrayList<Integer> l = new ArrayList<>();
        for (int i = 0; i < EVERYDAY.length; i++) {
            int w = EVERYDAY[i];
            if (list.contains(w))
                l.add(w);
        }
        return l;
    }

    public String parseConst(int c) {
        for (int i = 0; i < EVERYDAY.length; i++) {
            if (EVERYDAY[i] == c) {
                return context.getString(DAYS[i]);
            }
        }
        throw new RuntimeException("wrong day");
    }

    public int parseTag(Object o) {
        String s = (String) o;

        for (int i = 0; i < DAYS.length; i++) {
            String c = context.getResources().getString(DAYS[i]);
            if (s.compareTo(c) == 0)
                return EVERYDAY[i];
        }

        throw new RuntimeException("bad week");
    }

    public String getTimeString() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(time));

        int hour = c.get(Calendar.HOUR_OF_DAY);
        int min = c.get(Calendar.MINUTE);

        return String.format("%02d:%02d", hour, min);
    }

    public long getTime() {
        return time;
    }

    public void setTime(long l) {
        this.time = l;
    }

    // set today alarm
    public void setTime(int hour, int min) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date());
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, min);
        this.time = c.getTimeInMillis();
    }

    public int getHour() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(time));

        return c.get(Calendar.HOUR_OF_DAY);
    }

    public void setEnable(boolean e) {
        this.enable = e;
        setToday();
    }

    public boolean getEnable() {
        return enable;
    }

    public int getMin() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(time));

        return c.get(Calendar.MINUTE);
    }

    public Set<String> getWeekDays() {
        TreeSet<String> set = new TreeSet<>();
        for (Integer w : weekdaysValues) {
            set.add(w.toString());
        }
        return set;
    }

    public void setWeekDays(Set<String> set) {
        ArrayList w = new ArrayList<>();
        for (String s : set) {
            w.add(Integer.parseInt(s));
        }
        weekdaysValues = w;
    }

    public void setWeek(int week, boolean b) {
        weekdaysValues.remove(new Integer(week));
        if (b) {
            weekdaysValues.add(week);
        }
    }

    // check if 'week' in weekdays
    public boolean isWeek(int week) {
        for (Integer i : weekdaysValues) {
            if (i == week)
                return true;
        }
        return false;
    }

    // check if all 7 days are enabled (mon-sun)
    public boolean isEveryday() {
        for (Integer i : EVERYDAY) {
            if (!isWeek(i))
                return false;
        }
        return true;
    }

    // check if all 5 days are enabled (mon-fri)
    public boolean isWeekdays() {
        for (Integer i : WEEKDAY) {
            if (!isWeek(i))
                return false;
        }
        // check all weekend days are disabled
        for (Integer i : WEEKEND) {
            if (isWeek(i))
                return false;
        }
        return true;
    }

    // check if all 2 week days are enabled (sat, sun)
    public boolean isWeekend() {
        for (Integer i : WEEKEND) {
            if (!isWeek(i))
                return false;
        }
        // check all weekdays are disabled
        for (Integer i : WEEKDAY) {
            if (isWeek(i))
                return false;
        }
        return true;
    }

    public String getDays() {
        if (weekdays) {
            if (isEveryday()) {
                return "Everyday";
            }
            if (isWeekdays()) {
                return "Weekdays";
            }
            if (isWeekend()) {
                return "Weekend";
            }
            String str = "";
            for (Integer i : order(weekdaysValues)) {
                if (!str.isEmpty())
                    str += ", ";
                str += parseConst(i);
            }
            if (str.isEmpty())
                str = "No days selected"; // wrong, should not be allowed by UI
            return str;
        } else {
            if (isToday()) {
                return "Today";
            } else {
                return "Tomorrow";
            }
        }
    }

    // move alarm to tomorrow
    public void setTomorrow() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date(time));
        int ah = cal.get(Calendar.HOUR_OF_DAY);
        int am = cal.get(Calendar.MINUTE);

        cal.setTime(new Date());
        cal.add(Calendar.DATE, 1);
        cal.set(Calendar.HOUR_OF_DAY, ah);
        cal.set(Calendar.MINUTE, am);
        this.time = cal.getTimeInMillis();
    }

    public void setToday() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date(time));
        int ah = cal.get(Calendar.HOUR_OF_DAY);
        int am = cal.get(Calendar.MINUTE);

        cal.setTime(new Date());
        cal.set(Calendar.HOUR_OF_DAY, ah);
        cal.set(Calendar.MINUTE, am);
        this.time = cal.getTimeInMillis();
    }

    // If alarm time > current time == tomorrow. Or compare hours.
    public boolean isToday() {
        Calendar cur = Calendar.getInstance();

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(getAlarmTime(cur));

        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
        return fmt.format(cur.getTime()).equals(fmt.format(cal.getTime()));
    }

    public boolean isTomorrow() {
        Calendar cur = Calendar.getInstance();
        cur.add(Calendar.DATE, 1);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(getAlarmTime(cur));

        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
        return fmt.format(cur.getTime()).equals(fmt.format(cal.getTime()));
    }

    public Calendar rollWeek(Calendar cal) {
        long init = cal.getTimeInMillis();

        // check if alarm is active for current weekday. skip all disabled weekdays.
        int week = cal.get(Calendar.DAY_OF_WEEK);
        int i;
        for (i = 0; i < 7; i++) {
            // check week enabled?
            if (isWeek(week))
                break;
            // no, skip a day.
            cal.add(Calendar.DATE, 1);
            week = cal.get(Calendar.DAY_OF_WEEK);
        }
        if (i == 7) {
            // no weekday enabled. reset. use initial time, as if here were no weekdays checkbox enabled
            cal.setTimeInMillis(init);
        }
        return cal;
    }

    // get time for Alarm Manager
    public long getAlarmTime(Calendar cur) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(time);

        if (weekdays) {
            cal = rollWeek(cal);
        }

        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        if (cal.after(cur)) {
            // time is future? then it points for correct time.
            // change nothing, but seconds.
            return cal.getTimeInMillis();
        } else {
            int ch = cur.get(Calendar.HOUR_OF_DAY);
            int cm = cur.get(Calendar.MINUTE);

            int ah = cal.get(Calendar.HOUR_OF_DAY);
            int am = cal.get(Calendar.MINUTE);

            if ((ah < ch) || ((ah == ch) && (am <= cm))) {
                // if it too late to play, point to for tomorrow
                cal.add(Calendar.DATE, 1);
                cal = rollWeek(cal);
                return cal.getTimeInMillis();
            } else {
                // it is today alarm, fix day
                cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, ah);
                cal.set(Calendar.MINUTE, am);
                return cal.getTimeInMillis();
            }
        }
    }
}