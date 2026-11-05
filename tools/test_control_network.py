#!/usr/bin/env python3
"""Run the actual ControlCenter transport refresh method against deterministic network facades."""
import argparse
from pathlib import Path
import subprocess
import tempfile


HARNESS = r"""
package com.gathin.tvhome;
import java.util.*;
public final class ControlNetworkTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static class R {static class string {static final int not_connected=1,quick_network=2;}}
    static class Host {String getString(int id){return id==1?"Not connected":"Network";}}
    static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
    static class ConnectivityManager {NetworkStatus status; int reads;}
    static class NetworkStatus {
        enum Transport{NONE,WIFI,ETHERNET,OTHER}
        final Transport transport; final boolean online,vpn;
        NetworkStatus(Transport t,boolean o,boolean v){transport=t;online=o;vpn=v;}
        static NetworkStatus read(ConnectivityManager cm){cm.reads++;return cm.status;}
    }
    Host host=new Host();ConnectivityManager connectivity=new ConnectivityManager();
    String networkTransport;boolean networkOnline,networkEthernet,vpnConnected;
    int networkRevision,applies,synchronizations;
    void applyNetworkStatus(){applies++;}void syncWifi(){synchronizations++;}
    __ACTUAL_REFRESH_NETWORK__
    public static void main(String[] args){
        ControlNetworkTest view=new ControlNetworkTest();
        view.connectivity.status=new NetworkStatus(NetworkStatus.Transport.WIFI,true,false);view.refreshNetwork();
        check("Wi-Fi".equals(view.networkTransport)&&view.networkOnline&&!view.networkEthernet,"selected Wi-Fi route");
        view.refreshNetwork();check(view.networkRevision==1&&view.applies==1&&view.synchronizations==1,"quiet unchanged state");
        view.connectivity.status=new NetworkStatus(NetworkStatus.Transport.ETHERNET,false,false);view.refreshNetwork();
        check("Ethernet".equals(view.networkTransport)&&!view.networkOnline&&view.networkEthernet,"unvalidated default Ethernet");
        view.connectivity.status=new NetworkStatus(NetworkStatus.Transport.OTHER,true,true);view.refreshNetwork();
        check("VPN".equals(view.networkTransport)&&view.networkOnline&&!view.networkEthernet,"unknown VPN transport does not hide Wi-Fi");
        view.connectivity.status=new NetworkStatus(NetworkStatus.Transport.NONE,false,false);view.refreshNetwork();
        check("Not connected".equals(view.networkTransport),"disconnected");
        check(view.networkRevision==4&&view.applies==4&&view.synchronizations==4,"one update per meaningful change");
        System.out.println("ControlCenter.refreshNetwork: "+checks+" checks passed (actual method; transport policy tested in NetworkStatusTest)");
    }
}
"""


def extract_method(source, signature):
    start = source.index(signature)
    body = source.index("{", start)
    depth = 1
    end = body + 1
    while depth:
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = (root / "launcher/app/src/main/java/com/gathin/tvhome/ControlCenter.java").read_text(encoding="utf-8")
    method = extract_method(source, "void refreshNetwork()")
    java = args.java_home.resolve() / "bin"
    with tempfile.TemporaryDirectory(prefix="peachos-control-network-") as directory:
        temp = Path(directory)
        harness = temp / "ControlNetworkTest.java"
        harness.write_text(HARNESS.replace("__ACTUAL_REFRESH_NETWORK__", method), encoding="utf-8")
        subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "-d", str(temp), str(harness)], check=True)
        subprocess.run([str(java / "java.exe"), "-cp", str(temp), "com.gathin.tvhome.ControlNetworkTest"], check=True)


if __name__ == "__main__":
    main()
