package devsql;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** Minimal on-device SQLite runner (no sqlite3 binary on this ROM).
 *  usage: app_process /system/bin devsql.DeviceSql <dbpath> <sqlfile> [--readonly]
 */
public class DeviceSql {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("usage: DeviceSql <dbpath> <sqlfile> [--readonly]");
            return;
        }
        String dbPath = args[0];
        String sqlFile = args[1];
        boolean readOnly = args.length > 2 && "--readonly".equals(args[2]);
        List<String> stmts = new ArrayList<String>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(sqlFile));
            StringBuilder cur = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                cur.append(' ').append(line);
                if (t.endsWith(";")) {
                    String s = cur.toString().trim();
                    s = s.substring(0, s.length() - 1);
                    if (!s.isEmpty()) stmts.add(s);
                    cur.setLength(0);
                }
            }
            if (cur.toString().trim().length() > 0) stmts.add(cur.toString().trim());
            br.close();
        } catch (Throwable t) {
            System.out.println("ERR reading sql file: " + t);
            return;
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null,
                    readOnly ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.OPEN_READWRITE);
            System.out.println("# opened " + dbPath + " version=" + db.getVersion() + " readonly=" + db.isReadOnly());
            for (String s : stmts) {
                System.out.println("SQL> " + s);
                try {
                    if (s.trim().toUpperCase().startsWith("SELECT") || s.trim().toUpperCase().startsWith("PRAGMA")) {
                        Cursor c = db.rawQuery(s, null);
                        String[] cols = c.getColumnNames();
                        int n = 0;
                        while (c.moveToNext()) {
                            StringBuilder sb = new StringBuilder("ROW ");
                            for (int i = 0; i < cols.length; i++) {
                                sb.append(cols[i]).append('=');
                                try { sb.append(c.getString(i)); } catch (Throwable t) { sb.append('?'); }
                                sb.append(" | ");
                            }
                            System.out.println(sb.toString());
                            n++;
                        }
                        System.out.println("# rows=" + n);
                        c.close();
                    } else {
                        db.execSQL(s);
                        System.out.println("# ok");
                    }
                } catch (Throwable t) {
                    System.out.println("ERR " + t);
                }
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
        } finally {
            if (db != null) db.close();
        }
        System.out.println("DONE");
    }
}
