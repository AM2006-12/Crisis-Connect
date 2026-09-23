package com.crisisconnect.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.crisisconnect.R;
import com.crisisconnect.checkin.CheckInStatus;
import com.crisisconnect.sos.SOSManager;

import java.util.List;

/**
 * DashboardFragment
 * Shows: active peers count, alert list, SOS button, quick check-in buttons.
 *
 * All LiveData are observed in the fragment's view lifecycle.
 */
public class DashboardFragment extends Fragment {

    private CrisisViewModel viewModel;
    private AlertAdapter alertAdapter;
    private Button sosButton;
    private TextView peerCountText;
    private RecyclerView alertRecycler;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(CrisisViewModel.class);

        peerCountText = view.findViewById(R.id.peerCount);
        sosButton     = view.findViewById(R.id.sosButton);
        alertRecycler = view.findViewById(R.id.alertRecycler);

        alertAdapter = new AlertAdapter();
        alertRecycler.setAdapter(alertAdapter);
        alertRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));

        setupSOSButton();
        observeLiveData();
        setupCheckInButtons(view);
    }

    // ─── SOS Button ──────────────────────────────────────────────────────────

    private void setupSOSButton() {
        sosButton.setOnLongClickListener(v -> {
            viewModel.holdSOS();
            return true;
        });
        sosButton.setOnClickListener(v -> {
            SOSManager.SOSState state = viewModel.sosState.getValue();
            if (state == SOSManager.SOSState.ACTIVE) {
                viewModel.cancelSOS();
            } else {
                viewModel.releaseSOS();
            }
        });
    }

    // ─── LiveData Observation ─────────────────────────────────────────────────

    private void observeLiveData() {
        // Peer count
        viewModel.peers.observe(getViewLifecycleOwner(), peers ->
                peerCountText.setText(peers.size() + " peers connected"));

        // Alert list
        viewModel.alerts.observe(getViewLifecycleOwner(), alerts ->
                alertAdapter.submitList(alerts));

        // SOS countdown
        viewModel.sosCountdown.observe(getViewLifecycleOwner(), seconds -> {
            SOSManager.SOSState state = viewModel.sosState.getValue();
            if (state == SOSManager.SOSState.COUNTING_DOWN) {
                sosButton.setText("SOS in " + seconds + "...");
            }
        });

        // SOS state → button label and color
        viewModel.sosState.observe(getViewLifecycleOwner(), state -> {
            if (state == null) return;
            switch (state) {
                case IDLE:
                    sosButton.setText("Hold for SOS");
                    sosButton.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.sos_idle));
                    break;
                case COUNTING_DOWN:
                    sosButton.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.sos_countdown));
                    break;
                case ACTIVE:
                    sosButton.setText("SOS ACTIVE — Tap to Cancel");
                    sosButton.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), R.color.sos_active));
                    break;
            }
        });

        // Incoming SOS toast
        viewModel.incomingSOS.observe(getViewLifecycleOwner(), event -> {
            if (event == null) return;
            Toast.makeText(
                    requireContext(),
                    "SOS from " + event.senderName + " (" + event.hopCount + " hops away)",
                    Toast.LENGTH_LONG
            ).show();
        });
    }

    // ─── Quick Check-In Buttons ───────────────────────────────────────────────

    private void setupCheckInButtons(View view) {
        view.<Button>findViewById(R.id.btnSafe).setOnClickListener(v ->
                viewModel.checkIn(CheckInStatus.SAFE, "", "I am safe"));
        view.<Button>findViewById(R.id.btnNeedHelp).setOnClickListener(v ->
                viewModel.checkIn(CheckInStatus.NEED_HELP, "", "I need help"));
    }
}

// ─── Alert RecyclerView Adapter ───────────────────────────────────────────────

class AlertAdapter extends ListAdapter<CrisisViewModel.AlertItem, AlertAdapter.ViewHolder> {

    AlertAdapter() {
        super(new DiffUtil.ItemCallback<CrisisViewModel.AlertItem>() {
            @Override
            public boolean areItemsTheSame(@NonNull CrisisViewModel.AlertItem a,
                                           @NonNull CrisisViewModel.AlertItem b) {
                return a.timestamp == b.timestamp;
            }
            @Override
            public boolean areContentsTheSame(@NonNull CrisisViewModel.AlertItem a,
                                              @NonNull CrisisViewModel.AlertItem b) {
                return a.timestamp == b.timestamp && a.from.equals(b.from)
                        && a.content.equals(b.content) && a.hops == b.hops;
            }
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView desc;
        final TextView meta;

        ViewHolder(@NonNull View view) {
            super(view);
            title = view.findViewById(R.id.alertTitle);
            desc  = view.findViewById(R.id.alertDesc);
            meta  = view.findViewById(R.id.alertMeta);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alert, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CrisisViewModel.AlertItem item = getItem(position);
        holder.title.setText("Alert from " + item.from);
        holder.desc.setText(item.content);
        holder.meta.setText(item.hops + " hops · " + formatTime(item.timestamp));
    }

    private String formatTime(long ts) {
        long diffMinutes = (System.currentTimeMillis() - ts) / 60_000;
        return diffMinutes < 1 ? "just now" : diffMinutes + "m ago";
    }
}
