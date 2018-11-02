/*
 * Copyright 2015 OpenMarket Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.adapters;

import android.content.Context;
import android.content.Intent;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.rest.model.ReceiptData;
import org.matrix.androidsdk.rest.model.RoomMember;

import im.vector.R;
import im.vector.activity.VectorMemberDetailsActivity;
import im.vector.util.SystemUtilsKt;
import im.vector.util.VectorUtils;

/**
 * An adapter which can display read receipts
 */
public class VectorReadReceiptsAdapter extends ArrayAdapter<ReceiptData> {

    private final Context mContext;
    private final LayoutInflater mLayoutInflater;
    private final int mLayoutResourceId;
    private final MXSession mSession;
    private final Room mRoom;

    public VectorReadReceiptsAdapter(Context context, int layoutResourceId, MXSession session, Room room) {
        super(context, layoutResourceId);
        mContext = context;
        mLayoutResourceId = layoutResourceId;
        mLayoutInflater = LayoutInflater.from(mContext);
        mSession = session;
        mRoom = room;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mLayoutInflater.inflate(mLayoutResourceId, parent, false);
        }
        ReceiptData receipt = getItem(position);

        final TextView userNameTextView = convertView.findViewById(R.id.accountAdapter_name);
        final ImageView imageView = convertView.findViewById(R.id.avatar_img_vector);

        final RoomMember member = mRoom.getMember(receipt.userId);

        // if the room member is not known, display his user id.
        if (null == member) {
            userNameTextView.setText(receipt.userId);
            VectorUtils.loadUserAvatar(mContext, mSession, imageView, null, receipt.userId, receipt.userId);
        } else {
            userNameTextView.setText(member.getName());
            VectorUtils.loadRoomMemberAvatar(mContext, mSession, imageView, member);
        }

        TextView tsTextView = convertView.findViewById(R.id.read_receipt_ts);
        final String ts = AdapterUtils.tsToString(mContext, receipt.originServerTs, false);

        SpannableStringBuilder body = new SpannableStringBuilder(mContext.getString(im.vector.R.string.read_receipt) + " : " + ts);
        body.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, mContext.getString(im.vector.R.string.read_receipt).length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tsTextView.setText(body);

        userNameTextView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                SystemUtilsKt.copyToClipboard(mContext, userNameTextView.getText());
                return true;
            }
        });

        tsTextView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                SystemUtilsKt.copyToClipboard(mContext, ts);
                return true;
            }
        });

        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null != member) {
                    Intent startRoomInfoIntent = new Intent(mContext, VectorMemberDetailsActivity.class);
                    startRoomInfoIntent.putExtra(VectorMemberDetailsActivity.EXTRA_MEMBER_ID, member.getUserId());
                    startRoomInfoIntent.putExtra(VectorMemberDetailsActivity.EXTRA_ROOM_ID, mRoom.getRoomId());
                    startRoomInfoIntent.putExtra(VectorMemberDetailsActivity.EXTRA_MATRIX_ID, mSession.getCredentials().userId);
                    mContext.startActivity(startRoomInfoIntent);
                }
            }
        });

        return convertView;
    }
}
