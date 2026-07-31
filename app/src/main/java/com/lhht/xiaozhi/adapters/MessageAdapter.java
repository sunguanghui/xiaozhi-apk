package com.lhht.xiaozhi.adapters;

import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.models.Message;

import java.util.ArrayList;
import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
    private final List<Message> messages = new ArrayList<>();

    public void addMessage(Message message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        holder.messageText.setText(message.getText());

        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) holder.messageText.getLayoutParams();

        if (message.isFromServer()) {
            // AI 消息：左对齐，浅灰气泡，深色文字
            holder.messageText.setBackgroundResource(R.drawable.bg_ai_message);
            holder.messageText.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.bubble_ai_text));
            params.gravity = Gravity.START;
            params.setMarginStart(0);
            params.setMarginEnd(
                    (int) (holder.itemView.getContext().getResources().getDisplayMetrics().density * 72));
        } else {
            // 用户消息：右对齐，主色气泡，白色文字
            holder.messageText.setBackgroundResource(R.drawable.bg_user_message);
            holder.messageText.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.bubble_user_text));
            params.gravity = Gravity.END;
            params.setMarginStart(
                    (int) (holder.itemView.getContext().getResources().getDisplayMetrics().density * 72));
            params.setMarginEnd(0);
        }

        holder.messageText.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.messageText);
        }
    }
}
