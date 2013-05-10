package edu.berkeley.xtrace.localdaemon;

import org.apache.thrift.TException;
import java.util.Collection;

public class MasterServiceImpl implements MasterService.Iface {

    public Collection<Integer> daemons;

    public MasterServiceImpl(Collection<Integer> daemons) {
        this.daemons = daemons;
    }

    public void registerDaemon(long id, int port) throws TException {
        System.out.println("Register Daemon: (" + id + ", " + port + ")");
        daemons.add(port);
    }
}
