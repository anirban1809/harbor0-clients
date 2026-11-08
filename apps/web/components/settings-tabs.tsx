import type { ReactNode } from 'react';
import { Tab, TabList, TabPanel, Tabs } from './ui/tabs';

export type SettingsTab = 'Account' | 'Billing';

/** Settings split into the account's own settings and its plan, payments and invoices. */
export function SettingsTabs({
  tabs,
  tab,
  onChange,
  children,
}: {
  /** Without the Billing tab there is only Account, shown without tabs. */
  tabs: boolean;
  tab: SettingsTab;
  onChange: (tab: SettingsTab) => void;
  children: ReactNode;
}) {
  if (!tabs) return children;
  return (
    <Tabs className="shared-page" value={tab} onValueChange={onChange}>
      <TabList className="shared-tabs" aria-label="Settings sections">
        {(['Account', 'Billing'] as const).map((name) => (
          <Tab key={name} value={name} id={`settings-tab-${name}`}>
            {name}
          </Tab>
        ))}
      </TabList>
      <TabPanel value={tab} id="settings-content">
        {children}
      </TabPanel>
    </Tabs>
  );
}
