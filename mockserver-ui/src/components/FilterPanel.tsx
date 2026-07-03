import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Chip from '@mui/material/Chip';
import Collapse from '@mui/material/Collapse';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import AddCircleOutlineIcon from '@mui/icons-material/AddCircleOutlined';
import RemoveCircleOutlineIcon from '@mui/icons-material/RemoveCircleOutlined';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import FilterListIcon from '@mui/icons-material/FilterList';
import SaveIcon from '@mui/icons-material/Save';
import type { KeyToMultiValue, KeyToValue, RequestFilter } from '../types';
import { useDashboardStore } from '../store';
import { ACTION_TYPES, buildBodyMatcher, LLM_PROVIDERS, PROVIDER_DISPLAY } from '../lib/clientFilters';
import {
  deletePreset,
  loadPresets,
  savePresets,
  upsertPreset,
  validateRegex,
  type FilterPreset,
} from '../lib/filterPresets';

const HTTP_METHODS = ['', 'CONNECT', 'DELETE', 'GET', 'HEAD', 'OPTIONS', 'PATCH', 'POST', 'PUT', 'TRACE'];

// ---------------------------------------------------------------------------
// Multi-select chip cluster
// ---------------------------------------------------------------------------

interface ChipClusterProps {
  label: string;
  options: readonly string[];
  selected: string[];
  onChange: (selected: string[]) => void;
  displayMap?: Record<string, string>;
  disabled: boolean;
}

function ChipCluster({ label, options, selected, onChange, displayMap, disabled }: ChipClusterProps) {
  const toggle = (option: string) => {
    if (disabled) return;
    if (selected.includes(option)) {
      onChange(selected.filter((s) => s !== option));
    } else {
      onChange([...selected, option]);
    }
  };

  return (
    <Box sx={{ mb: 1 }}>
      <Typography variant="caption" color="primary" sx={{ mb: 0.5, display: 'block' }}>
        {label}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
        {options.map((option) => (
          <Chip
            key={option}
            label={displayMap?.[option] ?? option}
            size="small"
            variant={selected.includes(option) ? 'filled' : 'outlined'}
            color={selected.includes(option) ? 'primary' : 'default'}
            onClick={() => toggle(option)}
            disabled={disabled}
            sx={{ height: 24 }}
          />
        ))}
      </Box>
    </Box>
  );
}

interface MultiValueFieldProps {
  label: string;
  items: KeyToMultiValue[];
  onChange: (items: KeyToMultiValue[]) => void;
  disabled: boolean;
}

export function MultiValueField({ label, items, onChange, disabled }: MultiValueFieldProps) {
  const addRow = () => onChange([...items, { name: '', values: [''] }]);
  const removeRow = (i: number) => onChange(items.filter((_, idx) => idx !== i));
  const setName = (i: number, name: string) =>
    onChange(items.map((it, idx) => (idx === i ? { ...it, name } : it)));
  const setValue = (i: number, vi: number, val: string) =>
    onChange(
      items.map((it, idx) =>
        idx === i ? { ...it, values: it.values.map((v, j) => (j === vi ? val : v)) } : it,
      ),
    );
  const addValue = (i: number) =>
    onChange(items.map((it, idx) => (idx === i ? { ...it, values: [...it.values, ''] } : it)));
  const removeValue = (i: number, vi: number) =>
    onChange(
      items.map((it, idx) =>
        idx === i ? { ...it, values: it.values.filter((_, j) => j !== vi) } : it,
      ),
    );

  return (
    <Box sx={{ mb: 1 }}>
      <Typography variant="caption" color="primary" sx={{ mb: 0.5, display: 'block' }}>
        {label}
      </Typography>
      {items.map((item, i) => (
        <Box key={i} sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', mb: 0.5, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            label="Name"
            value={item.name}
            onChange={(e) => setName(i, e.target.value)}
            disabled={disabled}
            sx={{ width: { xs: '100%', sm: 140 } }}
          />
          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', flex: 1, minWidth: 0 }}>
            {item.values.map((val, vi) => (
              <Box key={vi} sx={{ display: 'flex', alignItems: 'center' }}>
                <TextField
                  size="small"
                  label="Value"
                  value={val}
                  onChange={(e) => setValue(i, vi, e.target.value)}
                  disabled={disabled}
                  sx={{ width: { xs: '100%', sm: 120 } }}
                />
                {vi > 0 && (
                  <Tooltip title="Remove value">
                    <IconButton size="small" disabled={disabled} onClick={() => removeValue(i, vi)} aria-label="Remove value">
                      <RemoveCircleOutlineIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                )}
              </Box>
            ))}
            <Tooltip title="Add value">
              <IconButton size="small" disabled={disabled} onClick={() => addValue(i)} aria-label="Add value">
                <AddCircleOutlineIcon fontSize="small" />
              </IconButton>
            </Tooltip>
          </Box>
          {i > 0 && (
            <Tooltip title="Remove filter row">
              <IconButton size="small" disabled={disabled} onClick={() => removeRow(i)} aria-label="Remove filter row">
                <RemoveCircleOutlineIcon fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
        </Box>
      ))}
      <Tooltip title="Add filter row">
        <IconButton size="small" disabled={disabled} onClick={addRow} aria-label="Add filter row">
          <AddCircleOutlineIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Box>
  );
}

interface SingleValueFieldProps {
  label: string;
  items: KeyToValue[];
  onChange: (items: KeyToValue[]) => void;
  disabled: boolean;
}

export function SingleValueField({ label, items, onChange, disabled }: SingleValueFieldProps) {
  const addRow = () => onChange([...items, { name: '', value: '' }]);
  const removeRow = (i: number) => onChange(items.filter((_, idx) => idx !== i));
  const setField = (i: number, field: 'name' | 'value', val: string) =>
    onChange(items.map((it, idx) => (idx === i ? { ...it, [field]: val } : it)));

  return (
    <Box sx={{ mb: 1 }}>
      <Typography variant="caption" color="primary" sx={{ mb: 0.5, display: 'block' }}>
        {label}
      </Typography>
      {items.map((item, i) => (
        <Box key={i} sx={{ display: 'flex', gap: 1, alignItems: 'center', mb: 0.5 }}>
          <TextField
            size="small"
            label="Name"
            value={item.name}
            onChange={(e) => setField(i, 'name', e.target.value)}
            disabled={disabled}
            sx={{ width: { xs: '100%', sm: 140 } }}
          />
          <TextField
            size="small"
            label="Value"
            value={item.value}
            onChange={(e) => setField(i, 'value', e.target.value)}
            disabled={disabled}
            sx={{ width: { xs: '100%', sm: 180 } }}
          />
          {i > 0 && (
            <Tooltip title="Remove filter row">
              <IconButton size="small" disabled={disabled} onClick={() => removeRow(i)} aria-label="Remove filter row">
                <RemoveCircleOutlineIcon fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
        </Box>
      ))}
      <Tooltip title="Add filter row">
        <IconButton size="small" disabled={disabled} onClick={addRow} aria-label="Add filter row">
          <AddCircleOutlineIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Box>
  );
}

interface FilterPanelProps {
  onFilterChange: (filter: RequestFilter) => void;
}

export default function FilterPanel({ onFilterChange }: FilterPanelProps) {
  const expanded = useDashboardStore((s) => s.filterExpanded);
  const toggleExpanded = useDashboardStore((s) => s.toggleFilterExpanded);
  const filterEnabled = useDashboardStore((s) => s.filterEnabled);
  const setFilterEnabled = useDashboardStore((s) => s.setFilterEnabled);
  const activeExpectations = useDashboardStore((s) => s.activeExpectations);
  const actionTypeFilter = useDashboardStore((s) => s.actionTypeFilter);
  const setActionTypeFilter = useDashboardStore((s) => s.setActionTypeFilter);
  const llmProviderFilter = useDashboardStore((s) => s.llmProviderFilter);
  const setLlmProviderFilter = useDashboardStore((s) => s.setLlmProviderFilter);
  const logShowForwarded = useDashboardStore((s) => s.logShowForwarded);
  const setLogShowForwarded = useDashboardStore((s) => s.setLogShowForwarded);

  const hasLlmExpectations = useMemo(
    () => activeExpectations.some((e) => 'httpLlmResponse' in e.value),
    [activeExpectations],
  );

  const [method, setMethod] = useState('');
  const [path, setPath] = useState('');
  const [body, setBody] = useState('');
  const [secure, setSecure] = useState(false);
  const [keepAlive, setKeepAlive] = useState(false);
  const [regex, setRegex] = useState(false);
  const [headers, setHeaders] = useState<KeyToMultiValue[]>([{ name: '', values: [''] }]);
  const [queryParams, setQueryParams] = useState<KeyToMultiValue[]>([{ name: '', values: [''] }]);
  const [cookies, setCookies] = useState<KeyToValue[]>([{ name: '', value: '' }]);

  // Saved filter presets (persisted to localStorage, like the theme).
  const [presets, setPresets] = useState<FilterPreset[]>(() => loadPresets());
  const [presetName, setPresetName] = useState('');

  // Validate the path as a regex only when regex mode is on; surfaces a subtle
  // error state instead of crashing or silently shipping a broken pattern.
  const pathRegexError = useMemo(
    () => (regex ? validateRegex(path).error : undefined),
    [regex, path],
  );

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const emitFilter = useCallback(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      if (!filterEnabled) {
        onFilterChange({});
        return;
      }
      const filter: RequestFilter = {};
      if (method) filter.method = method;
      // In regex mode an invalid pattern is held back rather than shipped to the
      // server (it would match nothing or error); the field shows the error.
      if (path && !(regex && validateRegex(path).error)) filter.path = path;
      // "Body contains" must be a substring (contains) match, not full-body
      // equality — see buildBodyMatcher for why the STRING/subString DTO is sent
      // rather than a bare string.
      if (body) filter.body = buildBodyMatcher(body);
      if (keepAlive) filter.keepAlive = true;
      if (secure) filter.secure = true;

      const validHeaders = headers.filter(
        (h) => h.name && h.values.some((v) => v),
      );
      if (validHeaders.length > 0) filter.headers = validHeaders;

      const validParams = queryParams.filter(
        (p) => p.name && p.values.some((v) => v),
      );
      if (validParams.length > 0) filter.queryStringParameters = validParams;

      const validCookies = cookies.filter((c) => c.name && c.value);
      if (validCookies.length > 0) filter.cookies = validCookies;

      onFilterChange(filter);
    }, 300);
  }, [filterEnabled, method, path, body, secure, keepAlive, regex, headers, queryParams, cookies, onFilterChange]);

  useEffect(() => {
    emitFilter();
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [emitFilter]);

  // Fields stay editable even while filtering is off so the first edit can arm it.
  const disabled = false;

  // Auto-enable: editing any filter field flips the "Enabled" switch on (no-op when
  // already on). The switch stays available for turning filtering back off.
  const arm = useCallback(() => {
    if (!filterEnabled) setFilterEnabled(true);
  }, [filterEnabled, setFilterEnabled]);

  const persist = useCallback((next: FilterPreset[]) => {
    setPresets(next);
    savePresets(next);
  }, []);

  const handleSavePreset = useCallback(() => {
    const name = presetName.trim();
    if (!name) return;
    const preset: FilterPreset = {
      name,
      method,
      path,
      body,
      secure,
      keepAlive,
      regex,
      headers,
      queryStringParameters: queryParams,
      cookies,
      actionTypeFilter,
      llmProviderFilter,
    };
    persist(upsertPreset(presets, preset));
    setPresetName('');
  }, [
    presetName, method, path, body, secure, keepAlive, regex, headers, queryParams, cookies,
    actionTypeFilter, llmProviderFilter, presets, persist,
  ]);

  const applyPreset = useCallback((preset: FilterPreset) => {
    setMethod(preset.method);
    setPath(preset.path);
    setBody(preset.body ?? '');
    setSecure(preset.secure);
    setKeepAlive(preset.keepAlive);
    setRegex(preset.regex);
    setHeaders(preset.headers && preset.headers.length > 0 ? preset.headers : [{ name: '', values: [''] }]);
    setQueryParams(
      preset.queryStringParameters && preset.queryStringParameters.length > 0
        ? preset.queryStringParameters
        : [{ name: '', values: [''] }],
    );
    setCookies(preset.cookies && preset.cookies.length > 0 ? preset.cookies : [{ name: '', value: '' }]);
    setActionTypeFilter(preset.actionTypeFilter);
    setLlmProviderFilter(preset.llmProviderFilter);
    // Applying a preset is only meaningful with filtering on.
    setFilterEnabled(true);
  }, [setActionTypeFilter, setLlmProviderFilter, setFilterEnabled]);

  const handleDeletePreset = useCallback((name: string) => {
    persist(deletePreset(presets, name));
  }, [presets, persist]);

  return (
    <Card variant="outlined" sx={{ mx: 1, mt: 1, flexShrink: 0 }}>
      <Box
        onClick={toggleExpanded}
        sx={{
          display: 'flex',
          alignItems: 'center',
          px: 2,
          py: 1,
          cursor: 'pointer',
          bgcolor: filterEnabled ? 'primary.main' : 'action.hover',
          color: filterEnabled ? 'primary.contrastText' : 'text.primary',
          '&:hover': { opacity: 0.9 },
        }}
      >
        <FilterListIcon sx={{ mr: 1 }} fontSize="small" />
        <Typography variant="subtitle2" sx={{ flex: 1 }}>
          Request Filter
        </Typography>
        {expanded ? <ExpandLessIcon /> : <ExpandMoreIcon />}
      </Box>
      <Collapse in={expanded}>
        <CardContent>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2 }}>
            <Box sx={{ minWidth: 100 }}>
              <FormControlLabel
                control={
                  <Switch
                    checked={filterEnabled}
                    onChange={(e) => setFilterEnabled(e.target.checked)}
                  />
                }
                label="Enabled"
              />
              {!filterEnabled && (
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: -0.5 }}>
                  Type in any field to start filtering.
                </Typography>
              )}
            </Box>
            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', flex: 1 }}>
              <TextField
                select
                size="small"
                label="Method"
                value={method}
                onChange={(e) => { arm(); setMethod(e.target.value); }}
                disabled={disabled}
                sx={{ width: { xs: '100%', sm: 130 } }}
              >
                {HTTP_METHODS.map((m) => (
                  <MenuItem key={m} value={m}>
                    {m || '(any)'}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                size="small"
                label={regex ? 'Path (regex)' : 'Path'}
                value={path}
                onChange={(e) => { arm(); setPath(e.target.value); }}
                disabled={disabled}
                error={Boolean(pathRegexError)}
                helperText={pathRegexError}
                sx={{ width: { xs: '100%', sm: 200 } }}
              />
              <TextField
                size="small"
                label="Body contains"
                value={body}
                onChange={(e) => { arm(); setBody(e.target.value); }}
                disabled={disabled}
                placeholder="text in the request body"
                sx={{ width: { xs: '100%', sm: 200 } }}
              />
              <FormControlLabel
                control={
                  <Switch
                    size="small"
                    checked={regex}
                    onChange={(e) => { arm(); setRegex(e.target.checked); }}
                    disabled={disabled}
                  />
                }
                label="Regex"
              />
              <FormControlLabel
                control={
                  <Switch
                    size="small"
                    checked={secure}
                    onChange={(e) => { arm(); setSecure(e.target.checked); }}
                    disabled={disabled}
                  />
                }
                label="Secure"
              />
              <FormControlLabel
                control={
                  <Switch
                    size="small"
                    checked={keepAlive}
                    onChange={(e) => { arm(); setKeepAlive(e.target.checked); }}
                    disabled={disabled}
                  />
                }
                label="Keep-Alive"
              />
            </Box>
          </Box>
          <Box sx={{ mt: 2 }}>
            <ChipCluster
              label="Action Type (expectations only)"
              options={ACTION_TYPES}
              selected={actionTypeFilter}
              onChange={(v) => { arm(); setActionTypeFilter(v); }}
              disabled={disabled}
            />
            {hasLlmExpectations && (
              <ChipCluster
                label="LLM Provider (expectations only)"
                options={LLM_PROVIDERS}
                selected={llmProviderFilter}
                onChange={(v) => { arm(); setLlmProviderFilter(v); }}
                displayMap={PROVIDER_DISPLAY}
                disabled={disabled}
              />
            )}
          </Box>
          <Box sx={{ mt: 1 }}>
            <Typography variant="caption" color="primary" sx={{ mb: 0.5, display: 'block' }}>
              Log Display
            </Typography>
            <FormControlLabel
              control={
                <Switch
                  size="small"
                  checked={logShowForwarded}
                  onChange={(e) => setLogShowForwarded(e.target.checked)}
                />
              }
              label="Show forwarded"
            />
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: -0.5 }}>
              Hide proxied / forwarded request entries from the Log panel when off.
            </Typography>
          </Box>
          <Box sx={{ mt: 2 }}>
            <MultiValueField label="Headers" items={headers} onChange={(v) => { arm(); setHeaders(v); }} disabled={disabled} />
            <SingleValueField label="Cookies" items={cookies} onChange={(v) => { arm(); setCookies(v); }} disabled={disabled} />
            <MultiValueField label="Query Parameters" items={queryParams} onChange={(v) => { arm(); setQueryParams(v); }} disabled={disabled} />
          </Box>
          <Box sx={{ mt: 2 }}>
            <Typography variant="caption" color="primary" sx={{ mb: 0.5, display: 'block' }}>
              Saved Presets
            </Typography>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mb: 1 }}>
              <TextField
                size="small"
                label="Preset name"
                value={presetName}
                onChange={(e) => setPresetName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    handleSavePreset();
                  }
                }}
                sx={{ width: { xs: '100%', sm: 200 } }}
              />
              <Button
                size="small"
                variant="outlined"
                startIcon={<SaveIcon fontSize="small" />}
                disabled={!presetName.trim()}
                onClick={handleSavePreset}
              >
                Save
              </Button>
            </Box>
            {presets.length === 0 ? (
              <Typography variant="caption" color="text.secondary">
                No saved presets. Configure a filter and save it to re-apply later.
              </Typography>
            ) : (
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                {presets.map((preset) => (
                  <Chip
                    key={preset.name}
                    label={preset.name}
                    size="small"
                    variant="outlined"
                    color="primary"
                    onClick={() => applyPreset(preset)}
                    onDelete={() => handleDeletePreset(preset.name)}
                    sx={{ height: 24 }}
                  />
                ))}
              </Box>
            )}
          </Box>
        </CardContent>
      </Collapse>
    </Card>
  );
}
