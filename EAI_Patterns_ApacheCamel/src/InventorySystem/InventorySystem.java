package InventorySystem;

import Order.Order;
import Order.OrderItem;
import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.processor.aggregate.AggregationStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 *   the   inventory   system   takes   transformed   order
 * messages (see below) and simply tests whether the requested items are
 * available.   Only   one   inventory   exists,   which   means   that   the
 * InventorySystems checks both types of items. Therefore, the inventory
 * system   modifies   the   valid   property   of   the   incoming   messages   and
 * optional modifies the validationResult property
 * <p>
 */
public class InventorySystem implements Processor {

    private static final String TCP_LOCALHOST_61616 = "tcp://localhost:61616";

    private static final String ORDER_TOPIC = "activemq:topic:ORDER?clientId=INVENTORY&durableSubscriptionName=INVENTORY";

    private static final String ITEM_STATUS = "activemq:queue:ITEM_ORDER_STATUS";

    /**
     * Process that does the split on divingsuits and surfboards
     */
    public void process(Exchange exchange) throws Exception {
        Message message = exchange.getIn();

        Order order = message.getBody(Order.class);

        // Create new Order Item based on the type of the item
        OrderItems object = new OrderItems();
        object.items.add(
                new OrderItem("DivingSuit", order.getOrderID(), order.getNumberOfDivingSuits(), order));
        object.items.add(
                new OrderItem("Surfboard", order.getOrderID(), order.getNumberOfSurfboards(), order));

        message.setBody(object);
    }

    /**
     * Best class in the world, setters and getters are needed, else it will crash!!!
     */
    public class OrderItems {
        List<OrderItem> items = new ArrayList<OrderItem>();


        public List<OrderItem> getItems() {
            return items;
        }

        public void setItems(List<OrderItem> items) {
            this.items = items;
        }


    }

    /**
     * Validates the order if in stock
     */
    static int validateOrder(Exchange exchange, int stockValue) {
        OrderItem order = exchange.getIn().getBody(OrderItem.class);

        int required = order.quantity;
        int available = 0;
        if (required <= stockValue) {
            stockValue -= required;
            available = required;
            exchange.getIn().setHeader("valid", true);
        } else {
            available = stockValue;
            exchange.getIn().setHeader("valid", false);
        }

        order.available = available;
        System.out.println("InventorySystem: " + order.toString());
        exchange.getIn().setBody(order);
        return stockValue;
    }

    public static boolean isSurfboard(@Body OrderItem orderItem) {
        return orderItem.type.equals("Surfboard");
    }

    /**
     * Strategy for aggregation
     */
    public static final class InventoryAggregationStrategy implements AggregationStrategy {

        public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
            if (oldExchange == null) {
                return newExchange;
            }

            List<OrderItem> order = new ArrayList<OrderItem>();
            order.add(oldExchange.getIn().getBody(OrderItem.class));
            order.add(newExchange.getIn().getBody(OrderItem.class));

            oldExchange.getIn().setBody(order);
            return oldExchange;
        }
    }

    private static volatile int surfboardsCount = 100;
    private static volatile int diveSuitCount = 100;

    /**
     * Starts the inventory system
     */
    public static void main(String[] args) {
        try {
            final InventorySystem inventoryTopicConsumer = new InventorySystem();

            // Create Camel Context
            DefaultCamelContext camelContext = new DefaultCamelContext();

            // Connect localhost ActiveMQ which should be separate process apache-activemq-5.14.3/bin$ ./activemq console
            ActiveMQComponent activeMQComponent = ActiveMQComponent.activeMQComponent(TCP_LOCALHOST_61616);
            camelContext.addComponent("activemq", activeMQComponent);
            camelContext.addRoutes(new RouteBuilder(camelContext) {
                @Override
                public void configure() throws Exception {
                    from(ORDER_TOPIC)
                            // InventoryCheck Splitter
                            .process(inventoryTopicConsumer)
                            .split(simple("${body.items}"))
                            // Process splitted items for Content-Based Router
                            .process(new Processor() {
                                public void process(Exchange exchange) throws Exception {
                                    Message message = exchange.getIn();
                                    OrderItem orderItem = message.getBody(OrderItem.class);
                                    message.setHeader("type", orderItem.type);
                                    message.setHeader("orderID", orderItem.orderID);
                                }
                            })
                            .choice()
                            // InventoryCheck Content-Based Router
                            .when(header("type").isEqualTo("Surfboard"))
                            .to("direct:surfboardsInventory")
                            .when(header("type").isEqualTo("DivingSuit"))
                            .to("direct:divingSuitsInventory")
                            .otherwise()
                            .to("direct:invalidOrder")
                            .end();

                    // InventoryCheck Routines
                    from("direct:surfboardsInventory")
                            .process(new Processor() {
                                public void process(Exchange exchange) throws Exception {
                                    surfboardsCount = validateOrder(exchange, surfboardsCount);
                                }
                            })
                            .to("direct:inventoryStatusAggr")
                            .end();

                    // InventoryCheck Routines
                    from("direct:divingSuitsInventory")
                            .process(new Processor() {
                                public void process(Exchange exchange) throws Exception {
                                    diveSuitCount = validateOrder(exchange, diveSuitCount);
                                }
                            })
                            .to("direct:inventoryStatusAggr")
                            .end();

                    // InventoryCheck Routines
                    from("direct:invalidOrder")
                            .to("direct:inventoryStatusAggr")
                            .end();

                    // InventoryCheck Aggregation
                    from("direct:inventoryStatusAggr")
                            .aggregate(header("orderID"), new InventoryAggregationStrategy()).completionPredicate(property(Exchange.AGGREGATED_SIZE).isEqualTo(2))
                            .process(new Processor() {
                                public void process(Exchange exchange) throws Exception {
                                    List<OrderItem> aggregatedInventoryCheck = exchange.getIn().getBody(ArrayList.class);

                                    Order order = null;
                                    Boolean valid = true;
                                    List<String> validationResults = new ArrayList<String>();

                                    for (OrderItem orderItem : aggregatedInventoryCheck) {
                                        if (orderItem.available != orderItem.quantity) {
                                            valid = false;
                                            validationResults.add("Not enought items available in " + orderItem.type
                                                    + ". Available: " + orderItem.available + ", Required: " + orderItem.quantity);
                                        }
                                        order = orderItem.parent;
                                    }


                                    order.setValid(valid);
                                    for (String validationResult : validationResults) {
                                        order.setValidationResult(validationResult);
                                    }
                                    System.out.println("Aggregated Order: " + exchange.getIn().getBody().toString());
                                    exchange.getIn().setBody(order);
                                    exchange.getIn().setHeader("type", "InventoryCheck");
                                }
                            })
                            .to(ITEM_STATUS)
                            .end();
                }
            });
            camelContext.start();

            runAddThread();

            System.in.read(); // wait till ENTER pressed

            camelContext.stop();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Adds new surfboard and divesuit every second
     */
    private static void runAddThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    surfboardsCount++;
                    diveSuitCount++;
                    System.out.println("Added new surfboard and divingsuit to inventory");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }
}
