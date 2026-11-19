/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.gtask.data;

import android.database.Cursor;
import android.util.Log;

import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * 便签元数据的远端载体：本身是一条 Google Task，notes 字段里装本地便签的完整 JSON。
 * 要解决的问题是——远端 Task 只有 name / notes 两个字段，装不下颜色、父目录、提醒时间。
 * 通过 related gid 与真正的任务节点一对一绑定，因此它不参与本地 JSON 互转与动作判定，
 * 对应的三个覆写方法一律抛 IllegalAccessError，把误用暴露在开发期而非同步期。
 * 顺序上必须先写任务、再写 meta，否则首次同步的 meta 找不到可关联的 gid。
 */
public class MetaData extends Task {
    private final static String TAG = MetaData.class.getSimpleName();

    private String mRelatedGid = null;

    public void setMeta(String gid, JSONObject metaInfo) {
        try {
            metaInfo.put(GTaskStringUtils.META_HEAD_GTASK_ID, gid);
        } catch (JSONException e) {
            Log.e(TAG, "failed to put related gid");
        }
        setNotes(metaInfo.toString());
        setName(GTaskStringUtils.META_NOTE_NAME);
    }

    public String getRelatedGid() {
        return mRelatedGid;
    }

    @Override
    public boolean isWorthSaving() {
        return getNotes() != null;
    }

    @Override
    public void setContentByRemoteJSON(JSONObject js) {
        super.setContentByRemoteJSON(js);
        if (getNotes() != null) {
            try {
                JSONObject metaInfo = new JSONObject(getNotes().trim());
                mRelatedGid = metaInfo.getString(GTaskStringUtils.META_HEAD_GTASK_ID);
            } catch (JSONException e) {
                Log.w(TAG, "failed to get related gid");
                mRelatedGid = null;
            }
        }
    }

    @Override
    public void setContentByLocalJSON(JSONObject js) {
        // this function should not be called
        throw new IllegalAccessError("MetaData:setContentByLocalJSON should not be called");
    }

    @Override
    public JSONObject getLocalJSONFromContent() {
        throw new IllegalAccessError("MetaData:getLocalJSONFromContent should not be called");
    }

    @Override
    public int getSyncAction(Cursor c) {
        throw new IllegalAccessError("MetaData:getSyncAction should not be called");
    }

}
