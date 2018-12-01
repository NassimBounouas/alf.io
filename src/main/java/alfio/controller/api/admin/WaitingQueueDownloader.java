package alfio.controller.api.admin;

import alfio.model.WaitingQueueSubscription;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class WaitingQueueDownloader {
    private Map<String, String> map;
    private Map<String, Consumer<WaitingQueueSubscription>> consumers;

    public WaitingQueueDownloader() {
        map = new HashMap<>();
        map.put("id", "ID");
        map.put("creation", "Creation");
        map.put("event", "Event");
        map.put("status", "Status");
        map.put("fullname", "Full Name");
        map.put("firstname", "First Name");
        map.put("lastname", "Last Name");
        map.put("email", "E-Mail");
        map.put("ticket_reservation_id", "Ticket Reservation Id");
        map.put("language", "Language");
        map.put("selected_category", "Selected category");
        map.put("subscription_type", "Subscription type");

        consumers = new HashMap<>();
        consumers.put("id", subscription -> System.out.println(subscription.getId()));
        consumers.put("creation", subscription -> System.out.println(subscription.getCreation()));
        consumers.put("event", subscription -> System.out.println(subscription.getEventId()));
        consumers.put("status", subscription -> System.out.println(subscription.getStatus()));
        consumers.put("fullname", subscription -> System.out.println(subscription.getFullName()));
        consumers.put("firstname", subscription -> System.out.println(subscription.getFirstName()));
        consumers.put("lastname", subscription -> System.out.println(subscription.getLastName()));
        consumers.put("email", subscription -> System.out.println(subscription.getEmailAddress()));
        consumers.put("ticket_reservation_id", subscription -> System.out.println(subscription.getReservationId()));
        consumers.put("language", subscription -> System.out.println(subscription.getUserLanguage()));
        consumers.put("selected_category", subscription -> System.out.println(subscription.getSelectedCategoryId()));
        consumers.put("subscription_type", subscription -> System.out.println(subscription.getSubscriptionType()));
    }

    public Map<String, String> availableFields() {
        return this.map;
    }

    public void extractDataToExport(List<WaitingQueueSubscription> subscriptions, List<String> fields) {
        for (WaitingQueueSubscription t : subscriptions) {
            fields.stream().filter(k -> this.consumers.containsKey(k)).forEach(k -> consumers.get(k).accept(t));
        }
    }
}
