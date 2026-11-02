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
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class R { private static final class string {static final int not_connected=1;} }
    private static final class Host {String getString(int id){return "Not connected";} }
    private static final class TextUtils {
        static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}
    }
    private static final class NetworkCapabilities {
        static final int TRANSPORT_WIFI=1,TRANSPORT_ETHERNET=3,TRANSPORT_VPN=4;
        static final int NET_CAPABILITY_VALIDATED=16;
        final int transport;final boolean validated;
        NetworkCapabilities(int transport,boolean validated){this.transport=transport;this.validated=validated;}
        boolean hasTransport(int value){return (transport&(1<<value))!=0;}
        boolean hasCapability(int value){return value==NET_CAPABILITY_VALIDATED&&validated;}
    }
    private static final class Network {
        final NetworkCapabilities capabilities;
        Network(NetworkCapabilities capabilities){this.capabilities=capabilities;}
    }
    private static final class ConnectivityManager {
        Network[] networks={};int enumerations;
        Network[] getAllNetworks(){enumerations++;return networks;}
        NetworkCapabilities getNetworkCapabilities(Network network){return network.capabilities;}
    }
    private final Host host=new Host();
    private ConnectivityManager connectivity=new ConnectivityManager();
    private String networkTransport;
    private boolean networkOnline,networkEthernet,vpnConnected;
    private int networkRevision,applies,synchronizations;
    private void applyNetworkStatus(){applies++;}
    private void syncWifi(){synchronizations++;}

    __ACTUAL_REFRESH_NETWORK__

    private static Network physical(int transport,boolean validated){
        return new Network(new NetworkCapabilities(1<<transport,validated));
    }
    private void expect(String transport,boolean online,boolean ethernet,boolean vpn,String message){
        check(transport.equals(networkTransport),message+": selected transport");
        check(networkOnline==online,message+": selected transport validation");
        check(networkEthernet==ethernet,message+": Ethernet priority");
        check(vpnConnected==vpn,message+": independent VPN state");
    }
    private static void permute(List<Network> input,int start,List<List<Network>> output){
        if(start==input.size()){output.add(new ArrayList<>(input));return;}
        for(int index=start;index<input.size();index++){
            Collections.swap(input,start,index);permute(input,start+1,output);Collections.swap(input,start,index);
        }
    }
    private static void everyOrder(List<Network> networks,String transport,boolean online,boolean ethernet,boolean vpn){
        List<List<Network>> orders=new ArrayList<>();permute(new ArrayList<>(networks),0,orders);
        for(List<Network> order:orders){
            ControlNetworkTest control=new ControlNetworkTest();
            control.connectivity.networks=order.toArray(new Network[0]);control.refreshNetwork();
            control.expect(transport,online,ethernet,vpn,"network enumeration order");
            check(control.networkRevision==1&&control.applies==1&&control.synchronizations==1,"one initial UI update");
        }
    }
    public static void main(String[] args){
        Network wifiOnline=physical(NetworkCapabilities.TRANSPORT_WIFI,true);
        Network wifiOffline=physical(NetworkCapabilities.TRANSPORT_WIFI,false);
        Network ethernetOnline=physical(NetworkCapabilities.TRANSPORT_ETHERNET,true);
        Network ethernetOffline=physical(NetworkCapabilities.TRANSPORT_ETHERNET,false);
        Network vpn=physical(NetworkCapabilities.TRANSPORT_VPN,true);

        // A validated Wi-Fi route must not lend its online flag to an unvalidated Ethernet link.
        everyOrder(Arrays.asList(wifiOnline,ethernetOffline,vpn),"Ethernet",false,true,true);
        // Validation is the union of all links belonging to the selected physical transport.
        everyOrder(Arrays.asList(ethernetOffline,ethernetOnline),"Ethernet",true,true,false);
        everyOrder(Arrays.asList(wifiOffline,wifiOnline,vpn),"Wi-Fi",true,false,true);
        everyOrder(Arrays.asList(wifiOnline,wifiOffline,ethernetOffline,ethernetOnline,vpn),"Ethernet",true,true,true);

        ControlNetworkTest unchanged=new ControlNetworkTest();
        unchanged.connectivity.networks=new Network[]{wifiOnline,vpn};unchanged.refreshNetwork();
        unchanged.expect("Wi-Fi",true,false,true,"Wi-Fi with VPN");
        unchanged.connectivity.networks=new Network[]{vpn,physical(NetworkCapabilities.TRANSPORT_WIFI,true)};
        unchanged.refreshNetwork();unchanged.refreshNetwork();
        check(unchanged.networkRevision==1,"new handles and order alone do not advance revision");
        check(unchanged.applies==1&&unchanged.synchronizations==1,"unchanged state does not redraw or resynchronize scanner");
        check(unchanged.connectivity.enumerations==3,"one enumeration per requested refresh");

        unchanged.connectivity.networks=new Network[]{ethernetOnline,wifiOnline,vpn};unchanged.refreshNetwork();
        unchanged.expect("Ethernet",true,true,true,"Ethernet becomes available");
        unchanged.connectivity.networks=new Network[]{wifiOffline,vpn};unchanged.refreshNetwork();
        unchanged.expect("Wi-Fi",false,false,true,"Ethernet lost with unvalidated Wi-Fi remaining");
        unchanged.connectivity.networks=new Network[]{vpn,new Network(null)};unchanged.refreshNetwork();
        unchanged.expect("Not connected",false,false,true,"physical routes lost while VPN handle remains");
        unchanged.connectivity.networks=new Network[0];unchanged.refreshNetwork();
        unchanged.expect("Not connected",false,false,false,"all networks lost");
        check(unchanged.networkRevision==5&&unchanged.applies==5&&unchanged.synchronizations==5,
                "each meaningful transport or VPN state change updates exactly once");
        unchanged.refreshNetwork();check(unchanged.applies==5,"repeated disconnected state is quiet");

        ControlNetworkTest validated=new ControlNetworkTest();
        validated.connectivity.networks=new Network[]{wifiOffline};validated.refreshNetwork();
        validated.expect("Wi-Fi",false,false,false,"captive or unvalidated connection");
        validated.connectivity.networks=new Network[]{wifiOnline};validated.refreshNetwork();
        validated.expect("Wi-Fi",true,false,false,"validation completes");
        check(validated.networkRevision==2,"validation change advances revision");

        ControlNetworkTest absent=new ControlNetworkTest();absent.connectivity=null;absent.refreshNetwork();
        absent.expect("Not connected",false,false,false,"connectivity service unavailable");
        Network vpnWithUnderlyingFlags=new Network(new NetworkCapabilities((1<<NetworkCapabilities.TRANSPORT_VPN)
                |(1<<NetworkCapabilities.TRANSPORT_WIFI)|(1<<NetworkCapabilities.TRANSPORT_ETHERNET),true));
        everyOrder(Arrays.asList(vpnWithUnderlyingFlags,new Network(null)),"Not connected",false,false,true);
        System.out.println("ControlCenter.refreshNetwork: "+checks+" checks passed (actual method, route order, validation and quiet updates)");
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
