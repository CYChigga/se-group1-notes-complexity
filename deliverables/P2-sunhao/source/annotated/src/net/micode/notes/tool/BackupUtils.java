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

package net.micode.notes.tool;

import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;


// 文本导出工具，串联存储状态检查数据库查询和文件输出
// 状态常量含有恢复相关命名，但本类公开实现主要是文本导出
// 日期命名使同日文件可能重用，应提醒调用方覆盖风险
public class BackupUtils {
    private static final String TAG = "BackupUtils";
    // Singleton stuff
    private static BackupUtils sInstance;

    // 以同步静态方法延迟初始化导出工具单例
    // 首次传入的上下文被持有，后续调用不会替换
    public static synchronized BackupUtils getInstance(Context context) {
        // 单例只创建一次，避免重复构造导出执行器
        if (sInstance == null) {
            sInstance = new BackupUtils(context);
        }
        return sInstance;
    }

    /**
     * Following states are signs to represents backup or restore
     * status
     */
    // Currently, the sdcard is not mounted
    public static final int STATE_SD_CARD_UNMOUONTED           = 0;
    // The backup file not exist
    public static final int STATE_BACKUP_FILE_NOT_EXIST        = 1;
    // The data is not well formated, may be changed by other programs
    public static final int STATE_DATA_DESTROIED               = 2;
    // Some run-time exception which causes restore or backup fails
    public static final int STATE_SYSTEM_ERROR                 = 3;
    // Backup or restore success
    public static final int STATE_SUCCESS                      = 4;

    private TextExport mTextExport;

    // 把上下文交给文本导出执行器
    // 当前外层只提供文本导出入口，没有实现完整备份恢复闭环
    private BackupUtils(Context context) {
        mTextExport = new TextExport(context);
    }

    // 判断外部存储是否处于可读写挂载状态
    // 只读挂载也不会通过该条件
    private static boolean externalStorageAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    // 外层入口委托给TextExport，内层执行实际导出
    // 内层先检查挂载状态和输出流，再按文件夹与根便签遍历
    // 导出正文和通话附属信息后关闭流，并返回状态码
    // 不能仅凭最终成功码证明所有底层PrintStream写入均无错
    public int exportToText() {
        return mTextExport.exportToText();
    }

    // 返回导出器缓存的最近输出文件名
    // 在成功创建文件流之前可能为空字符串
    public String getExportedTextFileName() {
        return mTextExport.mFileName;
    }

    // 返回导出器记录的目录配置字符串
    // 其值来自资源配置，不等同于拼接后的绝对路径
    public String getExportedTextFileDir() {
        return mTextExport.mFileDirectory;
    }

    private static class TextExport {
        private static final String[] NOTE_PROJECTION = {
                NoteColumns.ID,
                NoteColumns.MODIFIED_DATE,
                NoteColumns.SNIPPET,
                NoteColumns.TYPE
        };

        private static final int NOTE_COLUMN_ID = 0;

        private static final int NOTE_COLUMN_MODIFIED_DATE = 1;

        private static final int NOTE_COLUMN_SNIPPET = 2;

        private static final String[] DATA_PROJECTION = {
                DataColumns.CONTENT,
                DataColumns.MIME_TYPE,
                DataColumns.DATA1,
                DataColumns.DATA2,
                DataColumns.DATA3,
                DataColumns.DATA4,
        };

        private static final int DATA_COLUMN_CONTENT = 0;

        private static final int DATA_COLUMN_MIME_TYPE = 1;

        private static final int DATA_COLUMN_CALL_DATE = 2;

        private static final int DATA_COLUMN_PHONE_NUMBER = 4;

        private final String [] TEXT_FORMAT;
        private static final int FORMAT_FOLDER_NAME          = 0;
        private static final int FORMAT_NOTE_DATE            = 1;
        private static final int FORMAT_NOTE_CONTENT         = 2;

        private Context mContext;
        private String mFileName;
        private String mFileDirectory;

        // 读取文件夹日期正文三类导出格式模板
        // 将文件名与目录缓存置空，后续创建输出流时填写
        public TextExport(Context context) {
            TEXT_FORMAT = context.getResources().getStringArray(R.array.format_for_exported_note);
            mContext = context;
            mFileName = "";
            mFileDirectory = "";
        }

        // 按内部格式下标读取本地化模板
        // 调用方须保证资源数组长度和约定下标一致
        private String getFormat(int id) {
            return TEXT_FORMAT[id];
        }

        /**
         * Export the folder identified by folder id to text
         */
        // 按父文件夹编号查询便签并依次导出
        // 先打印修改日期，再委托导出每条便签数据
        // 游标在正常路径关闭，异常路径未使用finally保护
        private void exportFolderToText(String folderId, PrintStream ps) {
            // Query notes belong to this folder
            Cursor notesCursor = mContext.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION, NoteColumns.PARENT_ID + "=?", new String[] {
                        folderId
                    }, null);

            if (notesCursor != null) {
                if (notesCursor.moveToFirst()) {
                    do {
                        // Print note's last modified date
                        ps.println(String.format(getFormat(FORMAT_NOTE_DATE), DateFormat.format(
                                mContext.getString(R.string.format_datetime_mdhm),
                                notesCursor.getLong(NOTE_COLUMN_MODIFIED_DATE))));
                        // Query data belong to this note
                        String noteId = notesCursor.getString(NOTE_COLUMN_ID);
                        exportNoteToText(noteId, ps);
                    } while (notesCursor.moveToNext());
                }
                notesCursor.close();
            }
        }

        /**
         * Export note identified by id to a print stream
         */
        // 按便签编号遍历数据行，分别导出正文和通话信息
        // 电话号码及附件位置为空时跳过，通话日期仍按模板输出
        // 尾部写入固定字节作为分隔符，需另核对它是否符合目标文本格式
        // 游标释放依赖正常遍历结束，运行时异常可能影响释放
        private void exportNoteToText(String noteId, PrintStream ps) {
            Cursor dataCursor = mContext.getContentResolver().query(Notes.CONTENT_DATA_URI,
                    DATA_PROJECTION, DataColumns.NOTE_ID + "=?", new String[] {
                        noteId
                    }, null);

            if (dataCursor != null) {
                if (dataCursor.moveToFirst()) {
                    do {
                        String mimeType = dataCursor.getString(DATA_COLUMN_MIME_TYPE);
                        if (DataConstants.CALL_NOTE.equals(mimeType)) {
                            // Print phone number
                            String phoneNumber = dataCursor.getString(DATA_COLUMN_PHONE_NUMBER);
                            long callDate = dataCursor.getLong(DATA_COLUMN_CALL_DATE);
                            String location = dataCursor.getString(DATA_COLUMN_CONTENT);

                            if (!TextUtils.isEmpty(phoneNumber)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT),
                                        phoneNumber));
                            }
                            // Print call date
                            ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT), DateFormat
                                    .format(mContext.getString(R.string.format_datetime_mdhm),
                                            callDate)));
                            // Print call attachment location
                            if (!TextUtils.isEmpty(location)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT),
                                        location));
                            }
                        } else if (DataConstants.NOTE.equals(mimeType)) {
                            String content = dataCursor.getString(DATA_COLUMN_CONTENT);
                            if (!TextUtils.isEmpty(content)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT),
                                        content));
                            }
                        }
                    } while (dataCursor.moveToNext());
                }
                dataCursor.close();
            }
            // print a line separator between note
            try {
                ps.write(new byte[] {
                        Character.LINE_SEPARATOR, Character.LETTER_NUMBER
                });
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            }
        }

        /**
         * Note will be exported as text which is user readable
         */
        // 外层入口委托给TextExport，内层执行实际导出
        // 内层先检查挂载状态和输出流，再按文件夹与根便签遍历
        // 导出正文和通话附属信息后关闭流，并返回状态码
        // 不能仅凭最终成功码证明所有底层PrintStream写入均无错
        public int exportToText() {
            // 没有可写挂载介质时提前返回，不创建任何导出流
            if (!externalStorageAvailable()) {
                Log.d(TAG, "Media was not mounted");
                return STATE_SD_CARD_UNMOUONTED;
            }

            // 输出流创建失败需要转为系统错误状态，不能继续查询导出
            PrintStream ps = getExportToTextPrintStream();
            if (ps == null) {
                Log.e(TAG, "get print stream error");
                return STATE_SYSTEM_ERROR;
            }
            // First export folder and its notes
            Cursor folderCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    "(" + NoteColumns.TYPE + "=" + Notes.TYPE_FOLDER + " AND "
                            + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER + ") OR "
                            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER, null, null);

            if (folderCursor != null) {
                if (folderCursor.moveToFirst()) {
                    do {
                        // Print folder's name
                        String folderName = "";
                        // 通话文件夹使用本地化名称，普通文件夹使用数据库摘要
                        if(folderCursor.getLong(NOTE_COLUMN_ID) == Notes.ID_CALL_RECORD_FOLDER) {
                            folderName = mContext.getString(R.string.call_record_folder_name);
                        } else {
                            folderName = folderCursor.getString(NOTE_COLUMN_SNIPPET);
                        }
                        if (!TextUtils.isEmpty(folderName)) {
                            ps.println(String.format(getFormat(FORMAT_FOLDER_NAME), folderName));
                        }
                        String folderId = folderCursor.getString(NOTE_COLUMN_ID);
                        // 当前文件夹下的日期与正文由下一层逐条查询输出
                        exportFolderToText(folderId, ps);
                    } while (folderCursor.moveToNext());
                }
                folderCursor.close();
            }

            // Export notes in root's folder
            Cursor noteCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    NoteColumns.TYPE + "=" + +Notes.TYPE_NOTE + " AND " + NoteColumns.PARENT_ID
                            + "=0", null, null);

            if (noteCursor != null) {
                if (noteCursor.moveToFirst()) {
                    do {
                        ps.println(String.format(getFormat(FORMAT_NOTE_DATE), DateFormat.format(
                                mContext.getString(R.string.format_datetime_mdhm),
                                noteCursor.getLong(NOTE_COLUMN_MODIFIED_DATE))));
                        // Query data belong to this note
                        String noteId = noteCursor.getString(NOTE_COLUMN_ID);
                        exportNoteToText(noteId, ps);
                    } while (noteCursor.moveToNext());
                }
                noteCursor.close();
            }
            // 正常尾部释放文件流；中途异常不一定经过这里
            ps.close();

            return STATE_SUCCESS;
        }

        /**
         * Get a print stream pointed to the file {@generateExportedTextFile}
         */
        // 先生成外部存储目标文件并缓存文件名与目录
        // 打开文件输出流后包为PrintStream，异常返回空
        // 创建流失败时可能已留下文件，调用方不能仅凭文件存在判断成功
        private PrintStream getExportToTextPrintStream() {
            File file = generateFileMountedOnSDcard(mContext, R.string.file_path,
                    R.string.file_name_txt_format);
            if (file == null) {
                Log.e(TAG, "create file to exported failed");
                return null;
            }
            mFileName = file.getName();
            mFileDirectory = mContext.getString(R.string.file_path);
            PrintStream ps = null;
            try {
                FileOutputStream fos = new FileOutputStream(file);
                ps = new PrintStream(fos);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return null;
            } catch (NullPointerException e) {
                e.printStackTrace();
                return null;
            }
            return ps;
        }
    }

    /**
     * Generate the text file to store imported data
     */
    // 组合外部存储根目录资源路径和当前日期文件名
    // 仅调用mkdir创建单级目录，未检查创建动作返回值
    // 文件已存在时直接复用，上层非追加输出流会覆盖同名内容
    private static File generateFileMountedOnSDcard(Context context, int filePathResId, int fileNameFormatResId) {
        StringBuilder sb = new StringBuilder();
        sb.append(Environment.getExternalStorageDirectory());
        sb.append(context.getString(filePathResId));
        File filedir = new File(sb.toString());
        sb.append(context.getString(
                fileNameFormatResId,
                DateFormat.format(context.getString(R.string.format_date_ymd),
                        System.currentTimeMillis())));
        File file = new File(sb.toString());

        try {
            if (!filedir.exists()) {
                // 返回值没有被检查，失败原因要在后续文件创建或打开时才能体现
                filedir.mkdir();
            }
            if (!file.exists()) {
                // 已有同名日期文件不会重建，但后续FileOutputStream仍可能覆盖内容
                file.createNewFile();
            }
            return file;
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
}


