/*
 *
 *  * Copyright 2005-2016 Red Hat, Inc.
 *  * Red Hat licenses this file to you under the Apache License, version
 *  * 2.0 (the "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  * implied.  See the License for the specific language governing
 *  * permissions and limitations under the License.
 *
 */

package io.fabric8.msg.gateway;

import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
import org.apache.activemq.artemis.api.jms.JMSFactoryType;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.Message;
import javax.jms.MessageListener;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants.DIRECT_DELIVER;
import static org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants.HOST_PROP_NAME;
import static org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants.PORT_PROP_NAME;

public class ArtemisClient {
    private AtomicBoolean started = new AtomicBoolean();
    private final int port;
    private final String host;
    private JMSContext jmsContext;
    private Map<Destination,ProxyConsumer> consumerMap = new ConcurrentHashMap<>();

    public ArtemisClient(String host,int port){
        this.port=port;
        this.host=host;
    }



    public void start() throws Exception{
        if (started.compareAndSet(false,true)) {
            Map<String, Object> connectionParams = new HashMap<>();

            connectionParams.put(PORT_PROP_NAME, port);
            connectionParams.put(HOST_PROP_NAME, host);
            connectionParams.put(DIRECT_DELIVER, false);

            TransportConfiguration transportConfiguration =
                new TransportConfiguration(
                                              "org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory",
                                              connectionParams);

            ConnectionFactory connectionFactory = ActiveMQJMSClient.createConnectionFactoryWithoutHA(JMSFactoryType.CF, transportConfiguration);
            jmsContext = connectionFactory.createContext();
            jmsContext.start();
        }
    }

    public void stop() throws Exception{
        if (started.compareAndSet(true,false)){
            if (jmsContext != null) {
                jmsContext.stop();
            }
        }
    }

    public void send(Destination destination,Message message) throws Exception{
        jmsContext.createProducer().send(destination,message);
    }

    public void addConsumer(Destination destination, MessageListener listener){
        ProxyConsumer proxyConsumer = consumerMap.get(destination);
        if (proxyConsumer == null){
            proxyConsumer = new ProxyConsumer();
            proxyConsumer.listener=listener;
            proxyConsumer.destination=destination;
            proxyConsumer.jmsConsumer = jmsContext.createConsumer(destination);
            proxyConsumer = consumerMap.putIfAbsent(destination,proxyConsumer);
        }
        proxyConsumer.count.incrementAndGet();
    }

    public void removeConsumer(Destination destination){
        ProxyConsumer proxyConsumer = consumerMap.get(destination);
        if (proxyConsumer != null){
            if (proxyConsumer.count.decrementAndGet()<= 0){
                proxyConsumer=consumerMap.remove(destination);
                if (proxyConsumer != null) {
                    proxyConsumer.jmsConsumer.close();
                }
            }
        }
    }


    private class ProxyConsumer{
        Destination destination;
        AtomicInteger count = new AtomicInteger();
        MessageListener listener;
        JMSConsumer jmsConsumer;
    }

    public int hashCode(){
        int hash = 31;
        hash = 89  * hash + (host != null ? host.hashCode() : 0);
        hash = 89  * hash + (int) (port ^ (port >>> 32));
        return hash;
    }

    public boolean equals(Object object){
        if (object instanceof ArtemisClient){
            ArtemisClient other = (ArtemisClient) object;
            if (other.port==port && ((host == other.host) || host != null && other.host != null && host.equals(other.host))){
                return true;
            }
        }
        return false;
    }

    public String toString(){
        return ("ArtmeisClient["+host+":"+port+"]");
    }
}
