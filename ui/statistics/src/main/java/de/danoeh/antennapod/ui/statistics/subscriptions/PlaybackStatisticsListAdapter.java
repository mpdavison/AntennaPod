package de.danoeh.antennapod.ui.statistics.subscriptions;

import android.text.format.DateFormat;
import android.view.View;
import androidx.fragment.app.Fragment;
import de.danoeh.antennapod.storage.database.StatisticsItem;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.statistics.PieChartView;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsListAdapter;
import de.danoeh.antennapod.ui.statistics.feed.FeedStatisticsDialogFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for the playback statistics list.
 */
public class PlaybackStatisticsListAdapter extends StatisticsListAdapter {

    private final Fragment fragment;
    private long timeFilterFrom = 0;
    private long timeFilterTo = Long.MAX_VALUE;
    private boolean includeMarkedAsPlayed = false;
    private long totalAdTimeMs = 0;

    public PlaybackStatisticsListAdapter(Fragment fragment) {
        super(fragment.getContext());
        this.fragment = fragment;
    }

    public void setTimeFilter(boolean includeMarkedAsPlayed, long timeFilterFrom, long timeFilterTo) {
        this.includeMarkedAsPlayed = includeMarkedAsPlayed;
        this.timeFilterFrom = timeFilterFrom;
        this.timeFilterTo = timeFilterTo;
    }

    @Override
    protected String getHeaderCaption() {
        String caption;
        if (includeMarkedAsPlayed) {
            caption = context.getString(R.string.statistics_counting_total);
        } else {
            String skeleton = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMM yyyy");
            SimpleDateFormat dateFormat = new SimpleDateFormat(skeleton, Locale.getDefault());
            String dateFrom = dateFormat.format(new Date(timeFilterFrom));
            String dateTo = dateFormat.format(new Date(timeFilterTo - 24L * 3600000L));
            caption = context.getString(R.string.statistics_counting_range, dateFrom, dateTo);
        }
        String adStr = Converter.shortLocalizedDuration(context, totalAdTimeMs / 1000);
        return caption + "\n" + context.getString(R.string.statistics_ad_time_header, adStr);
    }

    @Override
    protected String getHeaderValue() {
        return Converter.shortLocalizedDuration(context, (long) pieChartData.getSum());
    }

    @Override
    protected PieChartView.PieChartData generateChartData(List<StatisticsItem> statisticsData) {
        float[] dataValues = new float[statisticsData.size()];
        totalAdTimeMs = 0;
        for (int i = 0; i < statisticsData.size(); i++) {
            StatisticsItem item = statisticsData.get(i);
            dataValues[i] = item.timePlayed;
            totalAdTimeMs += item.adTotalDurationMs;
        }
        return new PieChartView.PieChartData(dataValues);
    }

    @Override
    protected void onBindFeedViewHolder(StatisticsHolder holder, StatisticsItem statsItem) {
        String value = Converter.shortLocalizedDuration(context, statsItem.timePlayed);
        if (statsItem.adTotalDurationMs > 0) {
            value += ", " + Converter.shortLocalizedDuration(context, statsItem.adTotalDurationMs / 1000)
                    + " " + context.getString(R.string.statistics_ad_time_inline);
        } else {
            value += ", " + context.getString(R.string.statistics_no_ad_time);
        }
        holder.value.setText(value);
        holder.adValue.setVisibility(View.GONE);

        holder.itemView.setOnClickListener(v ->
                FeedStatisticsDialogFragment.newInstance(statsItem.feed.getId(), statsItem.feed.getTitle())
                        .show(fragment.getChildFragmentManager().beginTransaction(), "FeedStatistics"));
    }
}
