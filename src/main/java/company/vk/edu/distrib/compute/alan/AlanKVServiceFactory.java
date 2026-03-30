package company.vk.edu.distrib.compute.alan;

import company.vk.edu.distrib.compute.KVService;
import company.vk.edu.distrib.compute.KVServiceFactory;
import java.io.IOException;

public class AlanKVServiceFactory extends KVServiceFactory {
    @Override
    protected KVService doCreate(int port) throws IOException {
        return new AlanKVService(port, new IMDao());
    }
}
