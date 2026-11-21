/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Command line helper: java -cp peerconnect-server.jar com.peerconnect.server.AccountTool config/accounts.txt add USER PASSWORD | demo */
public final class AccountTool {
    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.out.println("usage: AccountTool <accountsFile> add <user> <password> | demo"); return; }
        Path f = Paths.get(a[0]);
        if (a[1].equals("demo")) { AccountStore.writeDemo(f); System.out.println("Demo accounts written to " + f); }
        else if (a[1].equals("add") && a.length == 4) { AccountStore.addAccount(f, a[2], a[3]); System.out.println("Account '" + a[2] + "' saved (PBKDF2 hash only)."); }
        else System.out.println("usage: AccountTool <accountsFile> add <user> <password> | demo");
    }
}
