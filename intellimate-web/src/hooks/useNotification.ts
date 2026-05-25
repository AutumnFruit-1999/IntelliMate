import { useRef, useCallback } from "react";

export function useNotification() {
  const permissionAsked = useRef(false);

  const requestPermission = useCallback(() => {
    if (permissionAsked.current) return;
    if (!("Notification" in window)) return;
    if (Notification.permission === "default") {
      permissionAsked.current = true;
      Notification.requestPermission();
    }
  }, []);

  const notify = useCallback((title: string, body: string) => {
    if (!("Notification" in window)) return;
    if (Notification.permission !== "granted") return;
    if (!document.hidden) return;

    const notification = new Notification(title, {
      body: body.slice(0, 100),
      icon: "/favicon.ico",
    });

    notification.onclick = () => {
      window.focus();
      notification.close();
    };

    setTimeout(() => notification.close(), 5000);
  }, []);

  return { requestPermission, notify };
}
