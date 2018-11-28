/**
 * This file is part of alf.io.
 * <p>
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.admin;

import alfio.controller.decorator.SaleableTicketCategory;
import alfio.manager.EventManager;
import alfio.manager.EventStatisticsManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.WaitingQueueManager;
import alfio.manager.system.ConfigurationManager;
import alfio.model.Event;
import alfio.model.WaitingQueueSubscription;
import alfio.model.modification.ConfigurationModification;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.util.EventUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static alfio.model.system.ConfigurationKeys.STOP_WAITING_QUEUE_SUBSCRIPTIONS;
import static alfio.util.OptionalWrapper.optionally;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@RestController
@RequestMapping("/admin/api/event/{eventName}/waiting-queue")
@AllArgsConstructor
public class AdminWaitingQueueApiController {

    private final WaitingQueueManager waitingQueueManager;
    private final EventManager eventManager;
    private final TicketReservationManager ticketReservationManager;
    private final ConfigurationManager configurationManager;
    private final EventStatisticsManager eventStatisticsManager;

    @RequestMapping(value = "/status", method = RequestMethod.GET)
    public Map<String, Boolean> getStatusForEvent(@PathVariable("eventName") String eventName, Principal principal) {
        return optionally(() -> eventManager.getSingleEvent(eventName, principal.getName()))
            .map(this::loadStatus)
            .orElse(Collections.emptyMap());
    }

    private Map<String, Boolean> loadStatus(Event event) {
        ZonedDateTime now = ZonedDateTime.now(event.getZoneId());
        List<SaleableTicketCategory> stcList = eventManager.loadTicketCategories(event)
            .stream()
            .filter(tc -> !tc.isAccessRestricted())
            .map(tc -> new SaleableTicketCategory(tc, "", now, event, ticketReservationManager.countAvailableTickets(event, tc), tc.getMaxTickets(), null))
            .collect(toList());
        boolean active = EventUtil.checkWaitingQueuePreconditions(event, stcList, configurationManager, eventStatisticsManager.noSeatsAvailable());
        boolean paused = active && configurationManager.getBooleanConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), STOP_WAITING_QUEUE_SUBSCRIPTIONS), false);
        Map<String, Boolean> result = new HashMap<>();
        result.put("active", active);
        result.put("paused", paused);
        return result;
    }

    @RequestMapping(value = "/status", method = RequestMethod.PUT)
    public Map<String, Boolean> setStatusForEvent(@PathVariable("eventName") String eventName, @RequestBody SetStatusForm form, Principal principal) {
        return optionally(() -> eventManager.getSingleEvent(eventName, principal.getName()))
            .map(event -> {
                configurationManager.saveAllEventConfiguration(event.getId(), event.getOrganizationId(),
                    singletonList(new ConfigurationModification(null, ConfigurationKeys.STOP_WAITING_QUEUE_SUBSCRIPTIONS.name(), String.valueOf(form.status))),
                    principal.getName());
                return loadStatus(event);
            }).orElse(Collections.emptyMap());
    }

    @RequestMapping(value = "/count", method = RequestMethod.GET)
    public Integer countWaitingPeople(@PathVariable("eventName") String eventName, Principal principal, HttpServletResponse response) {
        Optional<Integer> count = optionally(() -> eventManager.getSingleEvent(eventName, principal.getName())).map(e -> waitingQueueManager.countSubscribers(e.getId()));
        if (count.isPresent()) {
            return count.get();
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return 0;
    }

    @RequestMapping(value = "/load", method = RequestMethod.GET)
    public List<WaitingQueueSubscription> loadAllSubscriptions(@PathVariable("eventName") String eventName, Principal principal, HttpServletResponse response) {
        Optional<List<WaitingQueueSubscription>> count = optionally(() -> eventManager.getSingleEvent(eventName, principal.getName())).map(e -> waitingQueueManager.loadAllSubscriptionsForEvent(e.getId()));
        if (count.isPresent()) {
            return count.get();
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return Collections.emptyList();
    }

    @RequestMapping(value = "/subscriber/{subscriberId}", method = RequestMethod.DELETE)
    public ResponseEntity<Map<String, Object>> removeSubscriber(@PathVariable("eventName") String eventName,
                                                                @PathVariable("subscriberId") int subscriberId,
                                                                Principal principal) {
        return performStatusModification(eventName, subscriberId, principal, WaitingQueueSubscription.Status.CANCELLED, WaitingQueueSubscription.Status.WAITING);
    }

    @RequestMapping(value = "/subscriber/{subscriberId}/restore", method = RequestMethod.PUT)
    public ResponseEntity<Map<String, Object>> restoreSubscriber(@PathVariable("eventName") String eventName,
                                                                 @PathVariable("subscriberId") int subscriberId,
                                                                 Principal principal) {
        return performStatusModification(eventName, subscriberId, principal, WaitingQueueSubscription.Status.WAITING, WaitingQueueSubscription.Status.CANCELLED);
    }

    @RequestMapping("/fields")
    public List<SerializablePair<String, String>> getAllFields(@PathVariable("eventName") String eventName) {
        List<SerializablePair<String, String>> fields = new ArrayList<>();
        fields.addAll(WaitingQueueDownloader.availableFields().stream().map(f -> SerializablePair.of(f, f)).collect(toList()));
        return fields;
    }

    private Event loadEvent(String eventName, Principal principal) {
        Optional<Event> singleEvent = optionally(() -> eventManager.getSingleEvent(eventName, principal.getName()));
        Validate.isTrue(singleEvent.isPresent(), "event not found");
        return singleEvent.get();
    }

    @RequestMapping("/export")
    public void downloadWaitingQueue(@PathVariable("eventName") String eventName, @RequestParam(name = "format", defaultValue = "excel") String format, HttpServletRequest request, HttpServletResponse response, Principal principal) throws IOException {
        List<String> fields = Arrays.asList(Optional.ofNullable(request.getParameterValues("fields")).orElse(new String[]{}));
        Event event = loadEvent(eventName, principal);
        System.out.println("*** CALL ***");
        fields.stream().forEach(System.out::println);
        System.out.println(event.getDisplayName());
        /*Map<Integer, TicketCategory> categoriesMap = eventManager.loadTicketCategories(event).stream().collect(Collectors.toMap(TicketCategory::getId, Function.identity()));
        ZoneId eventZoneId = event.getZoneId();

        if ("excel".equals(format)) {
            exportTicketExcel(eventName, response, principal, fields, categoriesMap, eventZoneId);
        } else {
            exportTicketCSV(eventName, response, principal, fields, categoriesMap, eventZoneId);
        }*/
    }

    private ResponseEntity<Map<String, Object>> performStatusModification(String eventName, int subscriberId,
                                                                          Principal principal, WaitingQueueSubscription.Status newStatus,
                                                                          WaitingQueueSubscription.Status currentStatus) {
        return optionally(() -> eventManager.getSingleEvent(eventName, principal.getName()))
            .flatMap(e -> waitingQueueManager.updateSubscriptionStatus(subscriberId, newStatus, currentStatus).map(s -> Pair.of(s, e)))
            .map(pair -> {
                Map<String, Object> out = new HashMap<>();
                out.put("modified", pair.getLeft());
                out.put("list", waitingQueueManager.loadAllSubscriptionsForEvent(pair.getRight().getId()));
                return out;
            })
            .map(ResponseEntity::ok)
            .orElseGet(() -> new ResponseEntity<>(HttpStatus.BAD_REQUEST));
    }

    @Data
    private static class SetStatusForm {
        private boolean status;
    }


}
