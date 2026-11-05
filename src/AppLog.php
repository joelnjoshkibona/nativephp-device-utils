<?php

namespace Blutrixx\DeviceUtils;

use Throwable;

/**
 * The app's own log, kept on the phone and exportable (see README, "Logs"). On the phone every call goes to the native logger, which writes
 * a text file from native code (so it works when PHP or the web view is dead, and records crashes). Without the bridge (a development
 * server, tests) the same file format is written under storage/app/applog, so the screens and tests work the same.
 *
 * Never put personal content in a message: the person exports this file and shares it. Times, counts, codes and names of processes only.
 */
class AppLog
{
    private const MAX_BYTES = 400_000;

    private const KEEP = 3;

    private const LEVELS = 'DIWEF';

    public static function native(): bool
    {
        return function_exists('nativephp_call') && (bool) config('nativephp-internal.running');
    }

    private function call(string $function, array $params = []): array
    {
        $result = nativephp_call($function, json_encode($params === [] ? new \stdClass : $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }

    public function write(string $level, string $tag, string $message): void
    {
        $this->writeMany([['level' => $level, 'tag' => $tag, 'message' => $message]]);
    }

    public function info(string $tag, string $message): void
    {
        $this->write('I', $tag, $message);
    }

    public function warn(string $tag, string $message): void
    {
        $this->write('W', $tag, $message);
    }

    public function error(string $tag, string $message): void
    {
        $this->write('E', $tag, $message);
    }

    /** An exception as one line: class, file and line, and the message cut short with any SQL removed. */
    public function exception(Throwable $e, string $tag = 'php'): void
    {
        $this->error($tag, get_class($e).' at '.basename($e->getFile()).':'.$e->getLine().': '.self::clean($e->getMessage()));
    }

    /** @param  array<int, array{level?: string, tag?: string, message?: string}>  $entries */
    public function writeMany(array $entries): void
    {
        $entries = array_slice(array_values(array_filter($entries, 'is_array')), 0, 200);
        if (! $entries) {
            return;
        }
        if (self::native()) {
            $this->call('AppLog.Write', ['entries' => $entries]);

            return;
        }
        $lines = '';
        foreach ($entries as $e) {
            $level = strtoupper(substr((string) ($e['level'] ?? 'I'), 0, 1));
            $level = str_contains(self::LEVELS, $level) && $level !== '' ? $level : 'I';
            $tag = substr(preg_replace('/[^A-Za-z0-9_.-]/', '', (string) ($e['tag'] ?? 'app')) ?: 'app', 0, 24);
            $msg = str_replace("\r", '', substr((string) ($e['message'] ?? ''), 0, 4000));
            $parts = explode("\n", $msg);
            $lines .= date('Y-m-d H:i:s').'.'.str_pad((string) (int) (fmod(microtime(true), 1) * 1000), 3, '0', STR_PAD_LEFT)." $level $tag ".array_shift($parts)."\n";
            foreach ($parts as $p) {
                $lines .= "    $p\n";
            }
        }
        $file = $this->dir().'/app.log';
        if (is_file($file) && filesize($file) > self::MAX_BYTES) {
            $this->rotate();
        }
        @file_put_contents($file, $lines, FILE_APPEND | LOCK_EX);
    }

    /**
     * @return array{entries: array<int, string>, total: int, errors: int, detail: bool}
     */
    public function read(int $limit = 200, string $level = 'D'): array
    {
        if (self::native()) {
            $r = $this->call('AppLog.Read', ['limit' => $limit, 'level' => $level]);

            return ['entries' => $r['entries'] ?? [], 'total' => (int) ($r['total'] ?? 0), 'errors' => (int) ($r['errors'] ?? 0), 'detail' => (bool) ($r['detail'] ?? false)];
        }
        $entries = $this->entries();
        $rank = fn (string $e) => strpos(self::LEVELS, $e[24] ?? 'D');
        $min = max(0, (int) strpos(self::LEVELS, strtoupper($level)[0] ?? 'D'));
        $kept = array_values(array_filter($entries, fn ($e) => strlen($e) > 24 && $rank($e) >= $min));
        $errors = count(array_filter($entries, fn ($e) => strlen($e) > 24 && $rank($e) >= 3));

        return ['entries' => array_slice($kept, -max(1, $limit)), 'total' => count($entries), 'errors' => $errors, 'detail' => is_file($this->dir().'/detail')];
    }

    public function clear(): void
    {
        if (self::native()) {
            $this->call('AppLog.Clear');

            return;
        }
        foreach (glob($this->dir().'/app.log*') ?: [] as $f) {
            @unlink($f);
        }
        $this->write('I', 'app', 'log cleared by the person');
    }

    /** @return array{crashed: bool, at?: int, what?: string} */
    public function crash(): array
    {
        if (self::native()) {
            $r = $this->call('AppLog.Crash');

            return ['crashed' => (bool) ($r['crashed'] ?? false)] + (isset($r['at']) ? ['at' => (int) $r['at'], 'what' => (string) ($r['what'] ?? '')] : []);
        }

        return ['crashed' => false];
    }

    public function setDetail(bool $on): bool
    {
        if (self::native()) {
            return (bool) ($this->call('AppLog.SetDetail', ['on' => $on])['detail'] ?? false);
        }
        $flag = $this->dir().'/detail';
        $on ? @touch($flag) : @unlink($flag);
        $this->write('I', 'app', 'detailed place logging '.($on ? 'on' : 'off'));

        return $on;
    }

    /**
     * Save the log as a text file in Downloads/<app> and, with $share, open the share sheet for it. Without the bridge the file is written
     * to storage/app/applog/export.txt and {saved: true, path} says where.
     */
    public function export(bool $share = true, ?string $folder = null): array
    {
        if (self::native()) {
            return $this->call('AppLog.Export', array_filter(['share' => $share, 'folder' => $folder], fn ($v) => $v !== null));
        }
        $all = $this->read(PHP_INT_MAX);
        $out = $this->dir().'/export.txt';
        file_put_contents($out, "Log of the app (development server)\nExported ".date('Y-m-d H:i:s')."\nEntries: {$all['total']}, errors: {$all['errors']}\n----\n".implode("\n", $all['entries'])."\n");

        return ['saved' => true, 'path' => $out, 'shared' => false];
    }

    /** What may be written of an exception's message: no SQL (it carries the values), no connection details, 200 characters. */
    public static function clean(string $message): string
    {
        $message = preg_replace('/\s*\((?:SQL|Connection):.*$/s', '', $message) ?? $message;
        $message = trim(preg_replace('/\s+/', ' ', $message) ?? $message);

        return mb_substr($message, 0, 200);
    }

    private function dir(): string
    {
        $d = storage_path('app/applog');
        if (! is_dir($d)) {
            @mkdir($d, 0775, true);
        }

        return $d;
    }

    private function rotate(): void
    {
        $d = $this->dir();
        @unlink("$d/app.log.".(self::KEEP - 1));
        for ($i = self::KEEP - 2; $i >= 1; $i--) {
            @rename("$d/app.log.$i", "$d/app.log.".($i + 1));
        }
        @rename("$d/app.log", "$d/app.log.1");
    }

    /** @return array<int, string> every entry, oldest first (a multi-line entry is one element) */
    private function entries(): array
    {
        $entries = [];
        for ($i = self::KEEP - 1; $i >= 0; $i--) {
            $f = $this->dir().'/app.log'.($i ? ".$i" : '');
            if (! is_file($f)) {
                continue;
            }
            $cur = null;
            foreach (file($f, FILE_IGNORE_NEW_LINES) ?: [] as $line) {
                if (str_starts_with($line, '    ') && $cur !== null) {
                    $cur .= "\n".$line;
                } else {
                    if ($cur !== null) {
                        $entries[] = $cur;
                    }
                    $cur = $line;
                }
            }
            if ($cur !== null) {
                $entries[] = $cur;
            }
        }

        return $entries;
    }
}
